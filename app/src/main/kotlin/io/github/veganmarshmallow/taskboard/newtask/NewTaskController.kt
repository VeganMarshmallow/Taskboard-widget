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

/**
 * Owner of the New task form's [session], so the draft and an in-flight request
 * outlive the Activity (rotation recreates it). The request runs on its own thread; the result
 * is delivered on the main thread to the attached [listener], or kept in the session until a
 * recreated Activity takes it. A success also toasts and refreshes the widget right away
 * ([NewTaskEffects]), even if the form was closed meanwhile; a failure then toasts too, and the
 * reopened form still shows it with the draft intact.
 *
 * Process death: the draft and a submit marker go to disk ([SubmitJournal]); the first form of a
 * new process gets the draft back, and a marker left by a dead submit is checked against GitHub
 * ([SubmitRecovery]) before the user can submit again.
 *
 * One instance per process ([io.github.veganmarshmallow.taskboard.data.AppGraph.newTask]), so the
 * session survives Activity recreation. GitHub and threads come from [deps]. Main thread only.
 */
class NewTaskController(val deps: NewTaskDeps) {
    val session = NewTaskSession()

    /** The visible form, if any. */
    var listener: (() -> Unit)? = null

    /** The orphaned submit being checked against GitHub, if any. The form stays disabled meanwhile. */
    var checking: SubmitMarker? = null
        private set

    private var restored = false
    private var unshownRecovery: SubmitRecovery.Result? = null

    private fun journal(context: Context) = SubmitJournal(deps.store(context.applicationContext), deps.clock)

    /**
     * Called when a form opens. The first time in this process, takes back the draft a dead process
     * saved; then checks a submit a dead process left unfinished, if any.
     */
    fun restore(context: Context) {
        val app = context.applicationContext
        val journal = journal(app)
        if (!restored) {
            restored = true
            if (session.draft == TaskDraft.EMPTY) session.draft = journal.savedDraft()
        }
        if (checking != null) return
        val marker = journal.orphanedMarker(session.isSubmitting) ?: return
        checking = marker
        deps.sendExecutor.execute {
            val result = try {
                SubmitRecovery.check(marker, deps.clock.instant()) { since ->
                    deps.apiProvider(app)?.issuesUpdatedSince(since) ?: Outcome.Failure(BoardError.NoToken)
                }
            } catch (e: RuntimeException) {
                SubmitRecovery.Result.CheckFailed(marker.title, BoardError.Unexpected("internal error: ${e.javaClass.simpleName}"))
            }
            deps.mainThread.execute { recovered(app, result) }
        }
    }

    /** Persists what the form shows (on pause). */
    fun saveDraft(context: Context, draft: TaskDraft) = journal(context).saveDraft(draft)

    /** The form's Discard: forgets the draft here and on disk. Ignored while submitting. */
    fun discard(context: Context) {
        if (session.isSubmitting) return
        session.discard()
        journal(context).saveDraft(TaskDraft.EMPTY)
    }

    /** The finished recovery check not yet shown, once. */
    fun takeRecovery(): SubmitRecovery.Result? = unshownRecovery.also { unshownRecovery = null }

    /** Sends [issue] (from [NewTaskSession.start]). */
    fun send(context: Context, issue: NewIssue) {
        val app = context.applicationContext
        journal(app).sending(session.draft, issue)
        deps.sendExecutor.execute {
            val result = try {
                deps.apiProvider(app)?.createIssue(issue) ?: Outcome.Failure(BoardError.NoToken)
            } catch (e: RuntimeException) {
                Outcome.Failure(BoardError.Unexpected("internal error: ${e.javaClass.simpleName}"))
            }
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
        checking = null
        journal(app).recovered(result)
        if (result is SubmitRecovery.Result.Created) {
            session.discard()
            NewTaskEffects.refreshWidget(app)
        }
        unshownRecovery = result
        listener?.invoke()
    }
}
