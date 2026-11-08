package io.github.veganmarshmallow.taskboard.preview

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.preview.IssueComment
import io.github.veganmarshmallow.taskboard.core.preview.IssueDetails
import io.github.veganmarshmallow.taskboard.core.preview.PreviewLoader
import io.github.veganmarshmallow.taskboard.core.questions.AskService
import io.github.veganmarshmallow.taskboard.core.questions.Questions
import io.github.veganmarshmallow.taskboard.core.questions.QuestionsLoader
import io.github.veganmarshmallow.taskboard.core.reply.ReplyAction
import io.github.veganmarshmallow.taskboard.core.reply.ReplyService
import io.github.veganmarshmallow.taskboard.core.reply.ReplyStep
import io.github.veganmarshmallow.taskboard.core.work.guarded
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.widget.WidgetApprover
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs the preview's network work off the main thread and posts results back to it. Loads use the
 * preview's own thread, so opening a preview never waits behind a widget refresh. Approvals use
 * [WidgetApprover.approve], like the row's Approve button, so the widget refreshes afterwards. They,
 * replies and questions for Claude run on [SheetWrites]' thread, which [close] does not stop, so a
 * write finishes even if the preview closes. The Questions screen (task #82) loads through it too.
 * Results that arrive after [close] are dropped. Anything the work throws is reported as a failure ([guarded], task #47), so the preview never crashes or stays stuck on "Approving…".
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
        SheetWrites.executor.execute {
            val result = guarded { approver(app, number) }
            post { onResult(result) }
        }
    }

    /**
     * Sends a reply (task #44) on the [SheetWrites] thread, like [approve], then refreshes the
     * widget; the result goes to [ReplyDrafts].
     */
    fun reply(number: Int, action: ReplyAction) =
        SheetWrites.send(app, apiOrNull, ReplyDrafts, number, action.step) { ReplyService(it).send(number, action) }

    /**
     * Asks Claude [text] about [task], or in general when it is null (task #82), like [reply];
     * the result goes to [AskDrafts] under [key].
     */
    fun ask(key: Int, task: Int?, text: String) =
        SheetWrites.send(app, apiOrNull, AskDrafts, key, ReplyStep.COMMENT) { AskService(it).ask(task, text) }

    /** Loads the Questions screen (task #82) on this worker's own thread. */
    fun questions(onResult: (Outcome<Questions>) -> Unit) {
        executor.execute {
            val result = guarded { QuestionsLoader(apiOrNull(app)).load() }
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
}
