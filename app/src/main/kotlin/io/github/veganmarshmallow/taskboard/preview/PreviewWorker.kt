package io.github.veganmarshmallow.taskboard.preview

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.preview.IssueComment
import io.github.veganmarshmallow.taskboard.core.preview.IssueDetails
import io.github.veganmarshmallow.taskboard.core.preview.PreviewLoader
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.widget.WidgetApprover
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs the preview's network work off the main thread and posts results back to it. Loads use the
 * preview's own thread, so opening a preview never waits behind a widget refresh. Approvals use
 * [WidgetApprover.approve], like the row's Approve button, so the widget refreshes afterwards. They run
 * on one process-wide thread that [close] does not stop, so an approval finishes even if the preview
 * closes. Results that arrive after [close] are dropped.
 */
class PreviewWorker(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "taskboard-preview").apply { isDaemon = true }
    }

    @Volatile private var closed = false

    fun load(number: Int, onIssue: (Outcome<IssueDetails>) -> Unit, onComments: (List<IssueComment>) -> Unit) {
        executor.execute {
            PreviewLoader(AppGraph.apiOrNull(app)).load(
                number,
                onIssue = { result -> post { onIssue(result) } },
                onComments = { comments -> post { onComments(comments) } },
            )
        }
    }

    fun approve(number: Int, onResult: (Outcome<Unit>) -> Unit) {
        approvals.execute {
            val result = WidgetApprover(app).approve(number)
            post { onResult(result) }
        }
    }

    fun close() {
        closed = true
        executor.shutdownNow()
    }

    private fun post(block: () -> Unit) {
        main.post { if (!closed) block() }
    }

    private companion object {
        val approvals: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "taskboard-approve").apply { isDaemon = true }
        }
    }
}
