package io.github.veganmarshmallow.taskboard

import android.app.job.JobScheduler
import android.net.NetworkCapabilities
import android.os.Build
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.work.BoardJob
import io.github.veganmarshmallow.taskboard.core.work.RefreshMark
import io.github.veganmarshmallow.taskboard.core.work.RefreshTracker.Status
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.TestOverrides
import io.github.veganmarshmallow.taskboard.data.DeviceUptime
import io.github.veganmarshmallow.taskboard.data.RefreshMarkPrefs
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.widget.BoardJobs
import io.github.veganmarshmallow.taskboard.widget.WidgetRefresher
import io.github.veganmarshmallow.taskboard.widget.WidgetViews
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** #17 on a real device: refresh jobs run, end, and never leave the widget stuck on "updating…". */
@RunWith(AndroidJUnit4::class)
class RefreshJobTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val tracker get() = AppGraph.refreshTracker(context)
    private fun state() = StateStore(context).load()
    private val uptime = DeviceUptime(context)

    @Before @After fun reset() {
        context.getSystemService(JobScheduler::class.java).cancelAll()
        TestOverrides.httpClient = null
        TokenStore(context).clear()
        RefreshMarkPrefs(context).save(null)
        StateStore(context).save(WidgetState.INITIAL)
    }

    /** Polls [done]; if the job has not even started after a while, asks JobScheduler to run it now. */
    private fun waitFor(timeoutMs: Long, what: String, done: () -> Boolean) {
        val start = System.currentTimeMillis()
        var forced = false
        while (!done()) {
            val waited = System.currentTimeMillis() - start
            assertTrue("timed out waiting for $what", waited < timeoutMs)
            if (!forced && waited > 15_000 && tracker.status() == Status.IDLE) {
                forced = true
                instrumentation.uiAutomation
                    .executeShellCommand("cmd jobscheduler run -f ${context.packageName} ${BoardJob.REFRESH_ID}")
                    .close()
            }
            Thread.sleep(200)
        }
    }

    @Test fun aRefreshJobRunsAndClearsTheUpdatingState() {
        BoardJobs.refresh(context, RefreshTrigger.MANUAL)
        waitFor(60_000, "NoToken state") { state().error == BoardError.NoToken && tracker.status() == Status.IDLE }
        assertFalse(tracker.isUpdating())
    }

    @Test fun aStaleUpdatingStateFromADeadProcessRecovers() {
        val board = FakeBoards.realistic()
        StateStore(context).save(WidgetState(board, null, FakeBoards.NOW))
        // As if the process was killed mid-refresh two minutes ago: the mark was never cleared.
        RefreshMarkPrefs(context).save(RefreshMark(7, uptime.now().let { it.copy(elapsedMs = it.elapsedMs - 120_000) }))
        assertEquals(Status.STALE, tracker.status())
        assertFalse("a dead run never shows updating…", tracker.isUpdating())

        WidgetRefresher(context).recoverIfStale()
        assertEquals(Status.IDLE, tracker.status())
        assertEquals(BoardError.Timeout, state().error)
        assertEquals("the last good board stays", board, state().board)
    }

    @Test fun aFreshMarkShowsUpdatingAndAnAutomaticRefreshDoesNotInterruptIt() {
        RefreshMarkPrefs(context).save(RefreshMark(8, uptime.now()))
        assertTrue(tracker.isUpdating())
        BoardJobs.refresh(context, RefreshTrigger.AUTOMATIC)
        val pending = context.getSystemService(JobScheduler::class.java).getPendingJob(BoardJob.REFRESH_ID)
        assertEquals("automatic refreshes wait for the live run", null, pending)
    }

    @Test fun headerShowsUpdatingOnlyWhileARunIsLive() {
        val state = WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW)
        fun header(updating: Boolean): String {
            var text = ""
            instrumentation.runOnMainSync {
                val v = WidgetViews(context).build(state, 1, updating = updating).apply(context, FrameLayout(context))
                text = v.findViewById<TextView>(R.id.updated).text.toString()
            }
            return text
        }
        assertEquals(context.getString(R.string.updating), header(true))
        assertTrue(header(false).startsWith("last updated "))
    }

    /**
     * A server that keeps the refresh going far past goAsync's ~10 s. A manual refresh in the middle
     * replaces the stuck run; the refresh still ends in a timeout error, and nothing stays "updating…".
     */
    @Test fun aSlowServerEndsInATimeoutInsteadOfHangingForever() {
        TrickleServer().use { server ->
            TestOverrides.httpClient = server::client
            TokenStore(context).save("github_pat_TESTONLY")
            BoardJobs.refresh(context, RefreshTrigger.MANUAL)
            waitFor(60_000, "the refresh to start") { tracker.status() == Status.RUNNING }
            val started = System.currentTimeMillis()

            Thread.sleep(3_000)
            BoardJobs.refresh(context, RefreshTrigger.MANUAL) // the button works even while one is stuck
            waitFor(150_000, "the refresh to end") { tracker.status() == Status.IDLE }
            val tookMs = System.currentTimeMillis() - started

            assertTrue("ran past the broadcast limit: $tookMs ms", tookMs > 10_000)
            assertEquals(BoardError.Timeout, state().error)
            assertNotNull(state().attemptedAt)
            assertTrue("the server was really used", server.connections.get() >= 1)
        }
    }

    @Test fun aMarkFromAnotherBootIsDeadAtOnce() {
        val now = uptime.now()
        assumeTrue("the boot count is readable here", now.boot >= 0)
        RefreshMarkPrefs(context).save(RefreshMark(9, now.copy(boot = now.boot - 1)))
        assertEquals(Status.STALE, tracker.status())
        assertFalse(tracker.isUpdating())
    }

    @Test fun theSweepAfterAGoodRefreshKeepsTheBoard() {
        val good = WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW)
        StateStore(context).save(good)
        WidgetRefresher(context).sweep()
        assertEquals(good, state())
        assertEquals(Status.IDLE, tracker.status())
    }

    /** A tap on a VPN or hotspot: no wait for validation, and a deadline when not expedited. */
    @Test fun aManualRefreshJobAcceptsAVpnAndHasADeadline() {
        val info = BoardJobs.jobInfo(context, BoardJob.Refresh(RefreshTrigger.MANUAL), expedited = false)
        assertEquals(BoardJob.URGENT_DEADLINE.toMillis(), info.maxExecutionDelayMillis)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val net = info.requiredNetwork!!
            assertTrue(net.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            assertFalse("a VPN satisfies it", net.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
            assertFalse("no wait for validation", net.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }
        val auto = BoardJobs.jobInfo(context, BoardJob.Refresh(RefreshTrigger.AUTOMATIC), expedited = false)
        assertEquals("automatic refreshes have no deadline", 0L, auto.maxExecutionDelayMillis)
        val sweep = BoardJobs.jobInfo(context, BoardJob.Sweep, expedited = false)
        assertEquals(JobInfoNoNetwork, sweep.networkType)
        assertTrue(BoardJobs.schedule(context, BoardJob.Refresh(RefreshTrigger.MANUAL)))
    }

    private companion object {
        const val JobInfoNoNetwork = android.app.job.JobInfo.NETWORK_TYPE_NONE
    }
}
