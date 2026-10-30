package io.github.veganmarshmallow.taskboard.preview

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.preview.IssueComment
import io.github.veganmarshmallow.taskboard.core.preview.IssueDetails
import io.github.veganmarshmallow.taskboard.core.preview.PreviewLoader
import io.github.veganmarshmallow.taskboard.core.reply.ReplyAction
import io.github.veganmarshmallow.taskboard.core.reply.ReplyResult
import io.github.veganmarshmallow.taskboard.core.reply.ReplyService
import io.github.veganmarshmallow.taskboard.core.reply.ReplyStep
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger
import io.github.veganmarshmallow.taskboard.core.work.TimeLimit
import io.github.veganmarshmallow.taskboard.core.work.Timed
import io.github.veganmarshmallow.taskboard.core.work.crashError
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.widget.BackgroundWork
import io.github.veganmarshmallow.taskboard.widget.BoardJobs
import io.github.veganmarshmallow.taskboard.widget.WidgetRefresher
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs the preview's network work off the main thread and posts results back to it. Loads use the
 * preview's own thread, so opening a preview never waits behind a widget refresh. Approvals go through
 * the widget's [BackgroundWork] thread and [WidgetRefresher.approve], exactly like the row's Approve
 * button, so they can't race a refresh on the stored board and the widget refreshes afterwards.
 * Results that arrive after [close] are dropped.
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
        BackgroundWork.run(null) {
            val result = WidgetRefresher(app).approve(number)
            post { onResult(result) }
        }
    }

    /**
     * Sends a reply (task #44) on the widget's [BackgroundWork] thread like [approve], within
     * [REPLY_BUDGET], then refreshes the widget the way approve and merge do. A posted
     * reply clears its [ReplyDrafts] entry.
     */
    fun reply(number: Int, action: ReplyAction, onResult: (ReplyResult) -> Unit) {
        BackgroundWork.run(null) {
            val api = AppGraph.apiOrNull(app)
            val result = if (api == null) {
                ReplyResult.Failed(stepOf(action), BoardError.NoToken)
            } else {
                when (val timed = TimeLimit().run(REPLY_BUDGET) { ReplyService(api).send(number, action) }) {
                    is Timed.Done -> timed.value
                    is Timed.Crashed -> ReplyResult.Failed(stepOf(action), crashError(timed.error))
                    Timed.TimedOut -> ReplyResult.Failed(stepOf(action), BoardError.Timeout)
                }
            }
            val posted = result !is ReplyResult.Failed || result.step == ReplyStep.UNBLOCK
            if (posted) BoardJobs.refresh(app, RefreshTrigger.MANUAL)
            // Even when the sheet is gone by now: a posted reply must not come back as a draft.
            if (posted) main.post { ReplyDrafts.put(number, "") }
            post { onResult(result) }
        }
    }

    private fun stepOf(action: ReplyAction) = if (action is ReplyAction.Unblock) ReplyStep.UNBLOCK else ReplyStep.COMMENT

    fun close() {
        closed = true
        executor.shutdownNow()
    }

    private fun post(block: () -> Unit) {
        main.post { if (!closed) block() }
    }

    private companion object {
        val REPLY_BUDGET: Duration = Duration.ofSeconds(30)
    }
}
