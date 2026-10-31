package io.github.veganmarshmallow.taskboard

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.work.BoardJob
import io.github.veganmarshmallow.taskboard.core.work.RefreshTracker.Status
import io.github.veganmarshmallow.taskboard.core.work.RetryBackoff
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.TestOverrides
import io.github.veganmarshmallow.taskboard.data.RefreshMarkPrefs
import io.github.veganmarshmallow.taskboard.data.RetryStreakPrefs
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.data.TokenStore
import org.junit.Assert.assertTrue
import java.time.Duration

/**
 * Shared setup and waits for the #29 recovery tests: a [FlakyGitHub], a seconds-long backoff, and
 * a look at the pending refresh job. Waits never force a job to run: the point of these tests is
 * that the scheduled jobs run by themselves.
 */
class RecoveryHarness {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context: Context = instrumentation.targetContext
    val scheduler: JobScheduler = context.getSystemService(JobScheduler::class.java)
    val github = FlakyGitHub()

    fun state(): WidgetState = StateStore(context).load()

    /** A clean slate with the fake GitHub (down until told otherwise), [backoff] and a token. */
    fun setUp(backoff: RetryBackoff = RetryBackoff(listOf(QUICK), QUICK)) {
        reset()
        TestOverrides.retryBackoff = backoff
        TestOverrides.httpClient = { github }
        TokenStore(context).save("github_pat_TESTONLY")
    }

    /** Back to the real HTTP client and default backoff, with no jobs, token, mark, streak or board. */
    fun reset() {
        scheduler.cancelAll()
        TestOverrides.httpClient = null
        TestOverrides.retryBackoff = null
        TokenStore(context).clear()
        RefreshMarkPrefs(context).save(null)
        RetryStreakPrefs(context).save(null)
        StateStore(context).save(WidgetState.INITIAL)
    }

    fun waitUntil(timeoutMs: Long, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (done()) return true
            Thread.sleep(200)
        }
        return done()
    }

    fun await(timeoutMs: Long, what: String, done: () -> Boolean) =
        assertTrue("timed out waiting for $what (state: ${state()}, requests: ${github.requests})", waitUntil(timeoutMs, done))

    fun pendingRefresh(): Pair<JobInfo, BoardJob.Refresh>? {
        val info = scheduler.getPendingJob(BoardJob.REFRESH_ID) ?: return null
        val job = BoardJob.fromExtras(info.extras::getString) as? BoardJob.Refresh ?: return null
        return info to job
    }

    fun idle() = AppGraph.refreshTracker(context).status() == Status.IDLE

    fun recovered() = state().let { it.error == null && it.board != null }

    companion object {
        val QUICK: Duration = Duration.ofSeconds(5)
    }
}
