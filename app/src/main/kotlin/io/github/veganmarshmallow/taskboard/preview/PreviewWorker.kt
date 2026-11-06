package io.github.veganmarshmallow.taskboard.preview

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.preview.IssueComment
import io.github.veganmarshmallow.taskboard.core.preview.IssueDetails
import io.github.veganmarshmallow.taskboard.core.preview.PreviewLoader
import io.github.veganmarshmallow.taskboard.core.reply.ReplyAction
import io.github.veganmarshmallow.taskboard.core.reply.ReplyResult
import io.github.veganmarshmallow.taskboard.core.reply.ReplyService
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger
import io.github.veganmarshmallow.taskboard.core.work.TimeLimit
import io.github.veganmarshmallow.taskboard.core.work.Timed
import io.github.veganmarshmallow.taskboard.core.work.crashError
import io.github.veganmarshmallow.taskboard.core.work.guarded
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.widget.BoardJobs
import io.github.veganmarshmallow.taskboard.widget.WidgetApprover
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs the preview's network work off the main thread and posts results back to it. Loads use the
 * preview's own thread, so opening a preview never waits behind a widget refresh. Approvals use
 * [WidgetApprover.approve], like the row's Approve button, so the widget refreshes afterwards. They run
 * on one process-wide thread that [close] does not stop, so an approval finishes even if the preview
 * closes. Results that arrive after [close] are dropped. Anything the work throws is reported as a
 * failure ([guarded], task #47), so the preview never crashes or stays stuck on "Approving…".
 * [apiOrNull] and [approver] get the application context; they are seams for instrumented tests.
 */
class PreviewWorker(
    context: Context,
    private val apiOrNull: (Context) -> GitHubApi? = AppGraph::apiOrNull,
    private val approver: (Context, Int) -> Outcome<Unit> = { app, issue -> WidgetApprover(app).approve(issue) },
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "taskboard-preview").apply { isDaemon = true }
    }

    @Volatile private var closed = false

    fun load(number: Int, onIssue: (Outcome<IssueDetails>) -> Unit, onComments: (List<IssueComment>) -> Unit) {
        executor.execute {
            when (val api = guarded { Outcome.Success(apiOrNull(app)) }) {
                is Outcome.Failure -> post { onIssue(api) }
                is Outcome.Success -> PreviewLoader(api.value).load(
                    number,
                    onIssue = { result -> post { onIssue(result) } },
                    onComments = { comments -> post { onComments(comments) } },
                )
            }
        }
    }

    fun approve(number: Int, onResult: (Outcome<Unit>) -> Unit) {
        approvals.execute {
            val result = guarded { approver(app, number) }
            post { onResult(result) }
        }
    }

    /**
     * Sends a reply (task #44) on the same single `approvals` thread as [approve], within
     * [REPLY_BUDGET], then refreshes the widget the way approve and merge do. The result goes to
     * [ReplyDrafts], not to this worker's sheet: it must land even when that sheet was closed
     * meanwhile, and reach the sheet reopened in its place.
     */
    fun reply(number: Int, action: ReplyAction) {
        approvals.execute {
            val api = apiOrNull(app)
            val result = if (api == null) {
                ReplyResult.Failed(action.step, BoardError.NoToken)
            } else {
                when (val timed = TimeLimit().run(REPLY_BUDGET) { ReplyService(api).send(number, action) }) {
                    is Timed.Done -> timed.value
                    is Timed.Crashed -> ReplyResult.Failed(action.step, crashError(timed.error))
                    Timed.TimedOut -> ReplyResult.Failed(action.step, BoardError.Timeout)
                }
            }
            if (result.posted) BoardJobs.refresh(app, RefreshTrigger.MANUAL)
            main.post { ReplyDrafts.finished(number, result) }
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
        val REPLY_BUDGET: Duration = Duration.ofSeconds(30)
    }
}
