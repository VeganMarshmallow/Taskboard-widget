package io.github.veganmarshmallow.taskboard.newtask

import android.content.Context
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.task.CreatedIssue
import io.github.veganmarshmallow.taskboard.core.task.NewIssue
import io.github.veganmarshmallow.taskboard.core.task.NewTaskSession
import io.github.veganmarshmallow.taskboard.core.task.SubmitJournal
import io.github.veganmarshmallow.taskboard.core.task.SubmitMarker
import io.github.veganmarshmallow.taskboard.core.task.SubmitRecovery
import io.github.veganmarshmallow.taskboard.core.task.TaskDraft
import io.github.veganmarshmallow.taskboard.core.work.guarded

/**
 * Owner of the New task form's [session], so the draft and an in-flight request
 * outlive the Activity (rotation recreates it). The request runs on its own thread; the result
 * is delivered on the main thread to the attached [listener], or kept in the session until a
 * recreated Activity takes it. A success also toasts and refreshes the widget right away
 * ([NewTaskEffects]), even if the form was closed meanwhile; a failure then toasts too, and the
 * reopened form still shows it with the draft intact.
 *
 * Process death: the draft and a submit marker go to disk ([SubmitJournal]); the first form of a
 * new process gets the draft back, and a marker left by a dead submit (or by a request that timed
 * out) is checked against GitHub ([RecoveryCheck]) before the user can submit again.
 *
 * One instance per process ([io.github.veganmarshmallow.taskboard.data.AppGraph.newTask]), so the
 * session survives Activity recreation. GitHub and threads come from [deps]. Main thread only.
 */
class NewTaskController(val deps: NewTaskDeps) {
    val session = NewTaskSession()

    /** The visible form, if any. */
    var listener: (() -> Unit)? = null

    private val recovery = RecoveryCheck(deps)
    private var restored = false

    /** The orphaned submit being checked against GitHub, if any. The form stays disabled meanwhile. */
    val checking: SubmitMarker? get() = recovery.checking

    private fun journal(context: Context) = SubmitJournal(deps.store(context.applicationContext), deps.clock)

    /**
     * Called when a form opens. The first time in this process, takes back the draft a dead process
     * saved (unless this process already has one); then checks a submit that a dead process, or a
     * timed-out request, left unresolved.
     */
    fun restore(context: Context) {
        val app = context.applicationContext
        val journal = journal(app)
        if (!restored) {
            restored = true
            if (session.draft == TaskDraft.EMPTY) session.draft = journal.savedDraft()
        }
        val marker = journal.orphanedMarker(session.isSubmitting) ?: return
        recovery.start(app, marker) { result -> recovered(app, result) }
    }

    /** Persists what the form shows (on pause). */
    fun saveDraft(context: Context, draft: TaskDraft) = journal(context).saveDraft(draft)

    /** The form's Discard: forgets the draft here and on disk. Ignored while submitting. */
    fun discard(context: Context) {
        if (session.isSubmitting) return
        session.discard()
        journal(context).discarded()
    }

    /** The finished recovery check not yet shown, once. */
    fun takeRecovery(): SubmitRecovery.Result? = recovery.take()

    /** Sends [issue] (from [NewTaskSession.start]). */
    fun send(context: Context, issue: NewIssue) {
        val app = context.applicationContext
        journal(app).sending(session.draft, issue)
        deps.sendExecutor.execute {
            // Any crash, an Error included, is a failure ([guarded], task #56), so the send thread
            // survives and the form unlocks. A crash is not "may already exist": the marker is dropped.
            val result = guarded { deps.apiProvider(app)?.createIssue(issue) ?: Outcome.Failure(BoardError.NoToken) }
            deps.mainThread.execute { finish(app, result) }
        }
    }

    private fun finish(app: Context, result: Outcome<CreatedIssue>) {
        journal(app).completed(result)
        session.complete(result)
        when {
            result is Outcome.Success -> NewTaskEffects.created(app, result.value)
            listener == null -> NewTaskEffects.failedInBackground(app, result) // form was closed meanwhile
        }
        listener?.invoke()
    }

    private fun recovered(app: Context, result: SubmitRecovery.Result) {
        journal(app).recovered(result)
        if (result is SubmitRecovery.Result.Created) {
            session.discard()
            NewTaskEffects.refreshWidget(app)
        }
        listener?.invoke()
    }
}
