package io.github.veganmarshmallow.taskboard

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.PersistableBundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.veganmarshmallow.taskboard.RecoveryHarness.Companion.QUICK
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.work.BoardJob
import io.github.veganmarshmallow.taskboard.core.work.RefreshMark
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger
import io.github.veganmarshmallow.taskboard.core.work.RetryStreak
import io.github.veganmarshmallow.taskboard.data.DeviceUptime
import io.github.veganmarshmallow.taskboard.data.RefreshMarkPrefs
import io.github.veganmarshmallow.taskboard.data.RetryStreakPrefs
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.widget.AppUpdatedReceiver
import io.github.veganmarshmallow.taskboard.widget.BoardJobService
import io.github.veganmarshmallow.taskboard.widget.BoardJobs
import io.github.veganmarshmallow.taskboard.widget.BoardWidgetProvider
import io.github.veganmarshmallow.taskboard.widget.Connectivity
import io.github.veganmarshmallow.taskboard.widget.RetryScheduler
import io.github.veganmarshmallow.taskboard.widget.WidgetRenderer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/**
 * #29 review: the widget still recovers by itself when the usual retry chain was broken — by an
 * app update, by jobs the system dropped (force-stop), or by a refresh job that threw — and stops
 * retrying once no widget is left.
 */
@RunWith(AndroidJUnit4::class)
class RetryLifecycleTest {
    private val h = RecoveryHarness()
    private val context = h.context

    @Before fun setUp() {
        h.setUp()
        h.github.down = false
    }

    @After fun tearDown() = h.reset()

    /** The widget as an old version left it: on an error, with no refresh scheduled by this version. */
    private fun stuckOnAnError() = StateStore(context).save(WidgetState(FakeBoards.realistic(), BoardError.Unreachable, FakeBoards.NOW))

    /** A refresh the update killed mid-run: its mark still looks live for up to a minute. */
    private fun leaveADeadRunsFreshMark() = RefreshMarkPrefs(context).save(RefreshMark(42, DeviceUptime(context).now()))

    /** A refresh job as the version before #29 built it: a *validated* network, which a VPN may never get. */
    private fun leaveAnOldVersionsPendingJob() {
        val extras = PersistableBundle().apply {
            putString("kind", "refresh")
            putString("trigger", "AUTOMATIC")
        }
        val old = JobInfo.Builder(BoardJob.REFRESH_ID, ComponentName(context, BoardJobService::class.java))
            .setExtras(extras)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(Duration.ofHours(1).toMillis()) // stands in for "never runs on this VPN"
            .build()
        assertEquals(JobScheduler.RESULT_SUCCESS, h.scheduler.schedule(old))
    }

    private fun onUpdate() = BoardWidgetProvider().onUpdate(context, AppWidgetManager.getInstance(context), intArrayOf())

    @Test fun afterAnAppUpdateAStuckWidgetRecoversByItself() {
        assumeTrue("the emulator has a network", Connectivity.isOnline(context))
        stuckOnAnError()
        leaveADeadRunsFreshMark()
        leaveAnOldVersionsPendingJob()

        onUpdate() // what the system sends after an update: skipped, the dead run's mark looks live
        assertEquals("onUpdate alone leaves the old job in place", Duration.ofHours(1).toMillis(), h.pendingRefresh()?.first?.minLatencyMillis)
        assertEquals(0, h.github.requests)

        AppUpdatedReceiver.restartRefresh(context)
        h.await(60_000, "the widget to recover after the update, with no tap") { h.recovered() }
        assertTrue("the old job (1 hour away) was replaced", h.github.successes.get() >= 1)
    }

    @Test fun aWidgetUpdateRecoversAStuckWidgetWhoseJobsWereDropped() {
        assumeTrue("the emulator has a network", Connectivity.isOnline(context))
        stuckOnAnError() // and no jobs at all, as after a force-stop
        onUpdate()
        h.await(60_000, "the periodic update to recover the widget") { h.recovered() }
    }

    @Test fun theUpdateReceiverIsDeclaredAndIgnoresOtherActionsAndDevicesWithoutWidgets() {
        val declared = context.packageManager
            .queryBroadcastReceivers(Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(context.packageName), 0)
            .map { it.activityInfo.name }
        assertTrue("declared for MY_PACKAGE_REPLACED: $declared", AppUpdatedReceiver::class.java.name in declared)

        assumeTrue("no widget is placed on this emulator", WidgetRenderer(context).widgetIds().isEmpty())
        AppUpdatedReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        AppUpdatedReceiver().onReceive(context, Intent(Intent.ACTION_PACKAGE_ADDED))
        assertNull("nothing to recover without a widget", h.pendingRefresh())
    }

    @Test fun removingTheLastWidgetStopsRetrying() {
        RetryStreakPrefs(context).save(RetryStreak(4, DeviceUptime(context).now()))
        BoardJobs.schedule(context, BoardJob.Refresh(RefreshTrigger.RETRY, Duration.ofMinutes(30)))
        BoardWidgetProvider().onDisabled(context)
        assertNull(h.pendingRefresh())
        assertNull("the backoff starts over when a widget is placed again", RetryStreakPrefs(context).load())
    }

    @Test fun aRefreshJobThatThrewStillPlansItsRetry() {
        stuckOnAnError()
        RetryScheduler.afterCrash(context, BoardJob.Refresh(RefreshTrigger.RETRY, QUICK))
        val (info, job) = h.pendingRefresh() ?: throw AssertionError("no retry was planned")
        assertEquals(RefreshTrigger.RETRY, job.trigger)
        assertTrue(info.isPersisted)

        h.scheduler.cancelAll()
        StateStore(context).save(WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW))
        RetryScheduler.afterCrash(context, BoardJob.Refresh(RefreshTrigger.RETRY, QUICK))
        assertNull("a run that saved a good board needs no retry", h.pendingRefresh())
        assertNull(RetryStreakPrefs(context).load())
    }

    @Test fun aRefreshJobThatThrewOnAPermanentErrorPlansNoRetry() {
        StateStore(context).save(WidgetState(FakeBoards.realistic(), BoardError.BadToken(401), FakeBoards.NOW))
        RetryScheduler.afterCrash(context, BoardJob.Refresh(RefreshTrigger.AUTOMATIC))
        assertNull(h.pendingRefresh())
    }
}
