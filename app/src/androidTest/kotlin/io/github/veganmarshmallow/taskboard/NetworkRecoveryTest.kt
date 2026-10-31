package io.github.veganmarshmallow.taskboard

import android.app.job.JobInfo
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.veganmarshmallow.taskboard.RecoveryHarness.Companion.QUICK
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.work.BoardJob
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger
import io.github.veganmarshmallow.taskboard.core.work.RetryBackoff
import io.github.veganmarshmallow.taskboard.core.work.RetryStreak
import io.github.veganmarshmallow.taskboard.data.DeviceUptime
import io.github.veganmarshmallow.taskboard.data.RetryStreakPrefs
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.data.TestOverrides
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
    private val h = RecoveryHarness()
    private val context = h.context
    private val github = h.github
    private var wentOffline = false

    @Before fun setUp() = h.setUp()

    @After fun tearDown() {
        if (wentOffline) {
            setConnectivity(on = true)
            h.waitUntil(30_000) { Connectivity.isOnline(context) }
        }
        h.reset()
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(h.instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
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
        h.await(60_000, "the first refresh to fail") { h.state().error != null && h.idle() }
        assertEquals("online, so not the no-network wording", BoardError.Unreachable, h.state().error)
        val (info, job) = h.pendingRefresh() ?: throw AssertionError("no retry was scheduled")
        assertRetryJob(info, job)

        github.down = false
        h.await(90_000, "the widget to recover without a tap") { h.recovered() }
        assertTrue(github.failures.get() >= 1)
        assertTrue(github.successes.get() >= 1)
        h.await(10_000, "the finished retry to leave no further retry") { h.pendingRefresh() == null }
        assertNull("success resets the backoff", RetryStreakPrefs(context).load())
    }

    /** The device really goes offline (Wi-Fi and mobile data off), then comes back. */
    @Test fun anOfflineWidgetRefreshesAsSoonAsTheNetworkReturns() {
        assumeTrue("the emulator has a network", Connectivity.isOnline(context))
        github.down = false
        setConnectivity(on = false)
        assumeTrue("svc could not take this emulator offline", h.waitUntil(20_000) { !Connectivity.isOnline(context) })

        BoardJobs.refresh(context, RefreshTrigger.MANUAL) // the error-banner tap while offline
        assertEquals(BoardError.Network, h.state().error)
        val (info, job) = h.pendingRefresh() ?: throw AssertionError("no retry was scheduled")
        assertRetryJob(info, job)

        Thread.sleep(QUICK.toMillis() + 6_000) // past the backoff: the retry must still wait for a network
        assertEquals("no request while offline", 0, github.requests)
        assertEquals(BoardError.Network, h.state().error)

        setConnectivity(on = true)
        h.await(120_000, "the widget to recover once the network is back") { h.recovered() }
        assertTrue(github.successes.get() >= 1)
    }

    /** A tap on the error banner refreshes at once, replacing a long pending retry and restarting the backoff. */
    @Test fun tappingTheErrorBannerRetriesNowAndRestartsTheBackoff() {
        assumeTrue("the emulator has a network", Connectivity.isOnline(context))
        val first = Duration.ofSeconds(20) // long enough to look at the planned retry before it runs
        TestOverrides.retryBackoff = RetryBackoff(listOf(first), Duration.ofMinutes(30))
        StateStore(context).save(WidgetState(FakeBoards.realistic(), BoardError.Unreachable, FakeBoards.NOW))
        RetryStreakPrefs(context).save(RetryStreak(6, DeviceUptime(context).now()))
        BoardJobs.schedule(context, BoardJob.Refresh(RefreshTrigger.RETRY, Duration.ofMinutes(30)))

        WidgetActionReceiver().onReceive(context, WidgetIntents.refreshIntent(context))
        h.await(30_000, "the tap's refresh to reach GitHub") { github.requests >= 1 }
        h.await(30_000, "the tap's refresh to end") { h.idle() && github.requests >= 1 }
        h.await(10_000, "the next retry to be planned") { h.pendingRefresh()?.second?.trigger == RefreshTrigger.RETRY }
        assertEquals("the backoff started over", first.toMillis(), h.pendingRefresh()!!.first.minLatencyMillis)
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
}
