package io.github.veganmarshmallow.taskboard

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.work.BoardJob
import io.github.veganmarshmallow.taskboard.core.work.RefreshTracker.Status
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger
import io.github.veganmarshmallow.taskboard.core.work.RetryBackoff
import io.github.veganmarshmallow.taskboard.core.work.RetryStreak
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.DeviceUptime
import io.github.veganmarshmallow.taskboard.data.RefreshMarkPrefs
import io.github.veganmarshmallow.taskboard.data.RetryStreakPrefs
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.net.UrlConnectionHttpClient
import io.github.veganmarshmallow.taskboard.widget.BoardJobs
import io.github.veganmarshmallow.taskboard.widget.Connectivity
import io.github.veganmarshmallow.taskboard.widget.WidgetActionReceiver
import io.github.veganmarshmallow.taskboard.widget.WidgetIntents
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/**
 * #29 on a real device: after a network failure the widget recovers by itself through the
 * scheduled retry job, with no tap, once GitHub (or the device's network) is reachable again.
 */
@RunWith(AndroidJUnit4::class)
class NetworkRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private val github = FlakyGitHub()
    private var wentOffline = false
    private fun state() = StateStore(context).load()

    @Before fun setUp() {
        reset()
        // Seconds instead of minutes, so recovery is observable in a test.
        AppGraph.retryBackoff = RetryBackoff(listOf(QUICK), QUICK)
        AppGraph.httpClient = { github }
        TokenStore(context).save("github_pat_TESTONLY")
    }

    @After fun tearDown() {
        if (wentOffline) {
            setConnectivity(on = true)
            waitUntil(30_000) { Connectivity.isOnline(context) }
        }
        reset()
    }

    private fun reset() {
        scheduler.cancelAll()
        AppGraph.httpClient = { UrlConnectionHttpClient() }
        AppGraph.retryBackoff = RetryBackoff.DEFAULT
        TokenStore(context).clear()
        RefreshMarkPrefs(context).save(null)
        RetryStreakPrefs(context).save(null)
        StateStore(context).save(WidgetState.INITIAL)
    }

    private fun waitUntil(timeoutMs: Long, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (done()) return true
            Thread.sleep(200)
        }
        return done()
    }

    /** Waits without ever forcing a job to run: the point is that the scheduled job runs by itself. */
    private fun await(timeoutMs: Long, what: String, done: () -> Boolean) =
        assertTrue("timed out waiting for $what (state: ${state()}, requests: ${github.requests})", waitUntil(timeoutMs, done))

    private fun pendingRefresh(): Pair<JobInfo, BoardJob.Refresh>? {
        val info = scheduler.getPendingJob(BoardJob.REFRESH_ID) ?: return null
        val job = BoardJob.fromExtras(info.extras::getString) as? BoardJob.Refresh ?: return null
        return info to job
    }

    private fun recovered() = state().let { it.error == null && it.board != null }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun setConnectivity(on: Boolean) {
        if (!on) wentOffline = true
        val verb = if (on) "enable" else "disable"
        shell("svc wifi $verb")
        shell("svc data $verb")
    }

    private fun assertRetryJob(info: JobInfo, job: BoardJob.Refresh) {
        assertEquals(RefreshTrigger.RETRY, job.trigger)
        assertTrue("survives a reboot", info.isPersisted)
        assertEquals(QUICK.toMillis(), info.minLatencyMillis)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val net = info.requiredNetwork!!
            assertTrue(net.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            assertFalse("a VPN satisfies it", net.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
            assertFalse("no wait for validation", net.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        } else {
            assertEquals(JobInfo.NETWORK_TYPE_ANY, info.networkType)
        }
    }

    /** GitHub unreachable while the device is online (a VPN with broken DNS), then reachable again. */
    @Test fun aFailedRefreshRecoversByItselfThroughTheScheduledRetry() {
        assumeTrue("the emulator has a network", Connectivity.isOnline(context))
        BoardJobs.refresh(context, RefreshTrigger.MANUAL)
        await(60_000, "the first refresh to fail") { state().error != null && AppGraph.refreshTracker(context).status() == Status.IDLE }
        assertEquals("online, so not the no-network wording", BoardError.Unreachable, state().error)
        val (info, job) = pendingRefresh() ?: throw AssertionError("no retry was scheduled")
        assertRetryJob(info, job)

        github.down = false
        await(90_000, "the widget to recover without a tap") { recovered() }
        assertTrue(github.failures.get() >= 1)
        assertTrue(github.successes.get() >= 1)
        await(10_000, "the finished retry to leave no further retry") { pendingRefresh() == null }
        assertNull("success resets the backoff", RetryStreakPrefs(context).load())
    }

    /** The device really goes offline (Wi-Fi and mobile data off), then comes back. */
    @Test fun anOfflineWidgetRefreshesAsSoonAsTheNetworkReturns() {
        assumeTrue("the emulator has a network", Connectivity.isOnline(context))
        github.down = false
        setConnectivity(on = false)
        assumeTrue("svc could not take this emulator offline", waitUntil(20_000) { !Connectivity.isOnline(context) })

        BoardJobs.refresh(context, RefreshTrigger.MANUAL) // the error-banner tap while offline
        assertEquals(BoardError.Network, state().error)
        val (info, job) = pendingRefresh() ?: throw AssertionError("no retry was scheduled")
        assertRetryJob(info, job)

        Thread.sleep(QUICK.toMillis() + 6_000) // past the backoff: the retry must still wait for a network
        assertEquals("no request while offline", 0, github.requests)
        assertEquals(BoardError.Network, state().error)

        setConnectivity(on = true)
        await(120_000, "the widget to recover once the network is back") { recovered() }
        assertTrue(github.successes.get() >= 1)
    }

    /** A tap on the error banner refreshes at once, replacing a long pending retry and restarting the backoff. */
    @Test fun tappingTheErrorBannerRetriesNowAndRestartsTheBackoff() {
        assumeTrue("the emulator has a network", Connectivity.isOnline(context))
        val first = Duration.ofSeconds(20) // long enough to look at the planned retry before it runs
        AppGraph.retryBackoff = RetryBackoff(listOf(first), Duration.ofMinutes(30))
        StateStore(context).save(WidgetState(FakeBoards.realistic(), BoardError.Unreachable, FakeBoards.NOW))
        RetryStreakPrefs(context).save(RetryStreak(6, DeviceUptime(context).now()))
        BoardJobs.schedule(context, BoardJob.Refresh(RefreshTrigger.RETRY, Duration.ofMinutes(30)))

        WidgetActionReceiver().onReceive(context, WidgetIntents.refreshIntent(context))
        await(30_000, "the tap's refresh to reach GitHub") { github.requests >= 1 }
        await(30_000, "the tap's refresh to end") { AppGraph.refreshTracker(context).status() == Status.IDLE && github.requests >= 1 }
        await(10_000, "the next retry to be planned") { pendingRefresh()?.second?.trigger == RefreshTrigger.RETRY }
        assertEquals("the backoff started over", first.toMillis(), pendingRefresh()!!.first.minLatencyMillis)
        assertEquals(1, RetryStreakPrefs(context).load()!!.failures)
    }

    /** Every refresh job, background ones included, accepts a VPN and never waits for validation. */
    @Test fun backgroundRefreshJobsAcceptAnyInternetNetwork() {
        listOf(RefreshTrigger.AUTOMATIC, RefreshTrigger.RETRY).forEach { trigger ->
            val info = BoardJobs.jobInfo(context, BoardJob.Refresh(trigger), expedited = false)
            assertTrue("$trigger persisted", info.isPersisted)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val net = info.requiredNetwork!!
                assertFalse("$trigger", net.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
                assertFalse("$trigger", net.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
        }
        assertFalse(BoardJobs.jobInfo(context, BoardJob.Refresh(RefreshTrigger.MANUAL), expedited = false).isPersisted)
        assertTrue("a persisted job can be scheduled (RECEIVE_BOOT_COMPLETED is declared)", BoardJobs.schedule(context, BoardJob.Refresh(RefreshTrigger.RETRY, QUICK)))
    }

    private companion object {
        val QUICK: Duration = Duration.ofSeconds(5)
    }
}
