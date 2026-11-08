package io.github.veganmarshmallow.taskboard.preview

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.reply.ReplyMemory
import io.github.veganmarshmallow.taskboard.core.reply.ReplyResult
import io.github.veganmarshmallow.taskboard.core.reply.ReplyStep
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger
import io.github.veganmarshmallow.taskboard.core.work.TimeLimit
import io.github.veganmarshmallow.taskboard.core.work.Timed
import io.github.veganmarshmallow.taskboard.core.work.crashError
import io.github.veganmarshmallow.taskboard.widget.BoardJobs
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The one process-wide thread for the sheets' writes: approvals, replies (task #44) and questions
 * for Claude (task #82). Closing a sheet does not stop it, so a write always finishes.
 */
internal object SheetWrites {
    val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "taskboard-approve").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val BUDGET: Duration = Duration.ofSeconds(30)

    /**
     * Runs [work] within [BUDGET], then refreshes the widget when something was posted. The result
     * goes to [memory] under [key], not to the sheet that started it: it must land even when that
     * sheet was closed meanwhile, and reach the sheet reopened in its place. [step] is what a crash,
     * a timeout or a missing token is reported as.
     */
    fun send(app: Context, apiOrNull: (Context) -> GitHubApi?, memory: ReplyMemory, key: Int, step: ReplyStep, work: (GitHubApi) -> ReplyResult) {
        executor.execute {
            val api = apiOrNull(app)
            val result = if (api == null) {
                ReplyResult.Failed(step, BoardError.NoToken)
            } else {
                when (val timed = TimeLimit().run(BUDGET) { work(api) }) {
                    is Timed.Done -> timed.value
                    is Timed.Crashed -> ReplyResult.Failed(step, crashError(timed.error))
                    Timed.TimedOut -> ReplyResult.Failed(step, BoardError.Timeout)
                }
            }
            if (result.posted) BoardJobs.refresh(app, RefreshTrigger.MANUAL)
            main.post { memory.finished(key, result) }
        }
    }
}
