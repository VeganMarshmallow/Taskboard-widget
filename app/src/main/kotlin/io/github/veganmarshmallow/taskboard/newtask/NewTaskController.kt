package io.github.veganmarshmallow.taskboard.newtask

import android.content.Context
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.task.CreatedIssue
import io.github.veganmarshmallow.taskboard.core.task.NewIssue
import io.github.veganmarshmallow.taskboard.core.task.NewTaskSession

/**
 * Owner of the New task form's [session], so the draft and an in-flight request
 * outlive the Activity (rotation recreates it). The request runs on its own thread; the result
 * is delivered on the main thread to the attached [listener], or kept in the session until a
 * recreated Activity takes it. A success also toasts and refreshes the widget right away
 * ([NewTaskEffects]), even if the form was closed meanwhile; a failure then toasts too, and the
 * reopened form still shows it with the draft intact.
 *
 * One instance per process ([io.github.veganmarshmallow.taskboard.data.AppGraph.newTask]), so the
 * session survives Activity recreation. GitHub and threads come from [deps].
 */
class NewTaskController(val deps: NewTaskDeps) {
    val session = NewTaskSession()

    /** The visible form, if any. Main thread only. */
    var listener: (() -> Unit)? = null

    /** Sends [issue] (from [NewTaskSession.start]). Main thread only. */
    fun send(context: Context, issue: NewIssue) {
        val app = context.applicationContext
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
        session.complete(result)
        when {
            result is Outcome.Success -> NewTaskEffects.created(app, result.value)
            listener == null -> NewTaskEffects.failedInBackground(app, result) // form was closed meanwhile
        }
        listener?.invoke()
    }
}
