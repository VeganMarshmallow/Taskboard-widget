package io.github.veganmarshmallow.taskboard

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.work.RefreshMark
import io.github.veganmarshmallow.taskboard.data.DeviceUptime
import io.github.veganmarshmallow.taskboard.data.RefreshMarkPrefs
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.widget.BoardWidgetProvider
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #29 across a real app update: a widget stuck on an error recovers by itself after a new APK is
 * installed over the old one. An instrumentation cannot reinstall its own app, so
 * `.github/scripts/emulator-tests.sh` runs [prepare], then `adb install -r`, then [verify], each
 * selected with `-e appUpdateStep <name>`. Both are skipped in the normal test run.
 *
 * The real GitHub is used (the new process has no fakes) with a fake token, so the recovering
 * refresh ends in "bad token" or a network error; what matters is that it ran with no tap.
 */
@RunWith(AndroidJUnit4::class)
class AppUpdateRecoveryTest {
    private val h = RecoveryHarness()
    private val context = h.context
    private val step: String? = InstrumentationRegistry.getArguments().getString("appUpdateStep")

    @Test fun prepare() {
        assumeTrue(step == "prepare")
        h.reset()
        TokenStore(context).save("github_pat_TESTONLY")
        // A real widget instance, hosted by the app itself (the script ran `appwidget grantbind`).
        val id = AppWidgetHost(context, HOST_ID).allocateAppWidgetId()
        val bound = AppWidgetManager.getInstance(context)
            .bindAppWidgetIdIfAllowed(id, ComponentName(context, BoardWidgetProvider::class.java))
        assertTrue("bind permission granted by the script", bound)
        // Binding sends onUpdate, which refreshes: let that run end before the stuck state is set up.
        h.await(60_000, "the placement refresh to end") { h.state().attemptedAt != null && h.idle() }

        h.scheduler.cancelAll() // the old version's jobs are gone, as after a force-stop
        StateStore(context).save(STUCK)
        RefreshMarkPrefs(context).save(RefreshMark(42, DeviceUptime(context).now())) // a run the update kills
    }

    @Test fun verify() {
        assumeTrue(step == "verify")
        try {
            h.await(120_000, "a refresh after the update, with no tap") { h.state().attemptedAt != STUCK.attemptedAt && h.idle() }
            assertNotEquals(STUCK, h.state())
        } finally {
            AppWidgetHost(context, HOST_ID).deleteHost()
            h.reset()
        }
    }

    private companion object {
        const val HOST_ID = 2929
        val STUCK = WidgetState(FakeBoards.realistic(), BoardError.Unreachable, FakeBoards.NOW)
    }
}
