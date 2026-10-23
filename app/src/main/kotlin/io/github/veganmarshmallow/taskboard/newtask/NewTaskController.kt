package io.github.veganmarshmallow.taskboard.newtask

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.task.CreatedIssue
import io.github.veganmarshmallow.taskboard.core.task.NewIssue
import io.github.veganmarshmallow.taskboard.core.task.NewTaskSession
import io.github.veganmarshmallow.taskboard.data.AppGraph
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Process-wide owner of the New task form's [session], so the draft and an in-flight request
 * outlive the Activity (rotation recreates it). The request runs on its own thread; the result
 * is delivered on the main thread to the attached [listener], or kept in the session until a
 * recreated Activity takes it. A success also toasts and refreshes the widget right away
 * ([NewTaskEffects]), even if the form was closed meanwhile; a failure then toasts too, and the
 * reopened form still shows it with the draft intact.
 */
object NewTaskController {
    val session = NewTaskSession()

    /** The visible form, if any. Main thread only. */
    var listener: (() -> Unit)? = null

    /** Overridable in instrumented tests to fake GitHub. Null result = no token saved. */
    @Volatile var apiProvider: (Context) -> GitHubApi? = AppGraph::apiOrNull

    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "taskboard-new-task").apply { isDaemon = true }
    }

    /** Sends [issue] (from [NewTaskSession.start]). Main thread only. */
    fun send(context: Context, issue: NewIssue) {
        val app = context.applicationContext
        executor.execute {
            val result = try {
                apiProvider(app)?.createIssue(issue) ?: Outcome.Failure(BoardError.NoToken)
            } catch (e: RuntimeException) {
                Outcome.Failure(BoardError.Unexpected("internal error: ${e.javaClass.simpleName}"))
            }
            main.post { finish(app, result) }
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
