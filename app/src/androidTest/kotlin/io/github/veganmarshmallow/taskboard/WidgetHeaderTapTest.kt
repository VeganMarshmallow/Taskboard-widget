package io.github.veganmarshmallow.taskboard

import android.app.Instrumentation
import android.content.IntentFilter
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.widget.WidgetActionReceiver
import io.github.veganmarshmallow.taskboard.widget.WidgetIntents
import io.github.veganmarshmallow.taskboard.widget.WidgetViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task #16: tapping the widget header (title, last updated) opens nothing. Only explicit controls
 * carry click intents; the root carries a no-op so launchers don't open the app on stray taps.
 * (A RemoteViews click starts its PendingIntent via startIntentSender, which an Instrumentation
 * ActivityMonitor can't observe, so this checks which views are wired instead of tapping them.)
 */
@RunWith(AndroidJUnit4::class)
class WidgetHeaderTapTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun widget(state: WidgetState): View {
        lateinit var v: View
        instrumentation.runOnMainSync { v = WidgetViews(context).build(state, 1).apply(context, FrameLayout(context)) }
        return v
    }

    private fun View.clickable(id: Int) = findViewById<View>(id).hasOnClickListeners()

    private val states = listOf(
        WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW),
        WidgetState.INITIAL,
        WidgetState(null, BoardError.NoToken, FakeBoards.NOW),
        WidgetState(FakeBoards.realistic(), BoardError.Network, FakeBoards.NOW),
        WidgetState(null, BoardError.BadToken(401), FakeBoards.NOW),
        WidgetState(FakeBoards.realistic(), BoardError.NoAccess, FakeBoards.NOW),
        WidgetState(FakeBoards.realistic(), BoardError.RateLimited(null), FakeBoards.NOW),
    )

    private val setupErrors = listOf(BoardError.NoToken, BoardError.BadToken(401), BoardError.BadToken(403), BoardError.NoAccess)
    private val retryErrors = listOf(BoardError.Network, BoardError.Unreachable, BoardError.Timeout, BoardError.RateLimited(null), BoardError.Server(502), BoardError.Unexpected("HTTP 418"))

    @Test fun headerTextHasNoClickIntent() = states.forEach { state ->
        val v = widget(state)
        assertFalse("$state title", v.clickable(R.id.title))
        assertFalse("$state updated", v.clickable(R.id.updated))
        assertFalse("$state header row", (v.findViewById<View>(R.id.title).parent as View).hasOnClickListeners())
    }

    @Test fun explicitControlsAndTheNoOpRootAreWired() = states.forEach { state ->
        val v = widget(state)
        assertTrue(v.clickable(R.id.refresh))
        assertTrue(v.clickable(R.id.new_task))
        assertTrue("root swallows stray taps", v.clickable(WidgetViews.ROOT))
    }

    @Test fun errorBannerIsTheOnlyWidgetPathToSetup() {
        assertTrue(widget(WidgetState(null, BoardError.NoToken, FakeBoards.NOW)).clickable(R.id.error))
        assertFalse(widget(WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW)).clickable(R.id.error))
    }

    /** The background and the empty/loading text have no target of their own: a tap there hits the no-op root. */
    @Test fun backgroundAndEmptyAreasHaveNoTapTarget() = states.forEach { state ->
        val v = widget(state)
        assertFalse("$state empty text", v.clickable(R.id.board_empty))
        assertFalse("$state list container", (v.findViewById<View>(R.id.board_list).parent as View).hasOnClickListeners())
    }

    /** The no-op the root sends starts no activity (in particular not the token setup) and doesn't crash. */
    @Test fun noOpBroadcastOpensNothing() {
        val monitor: Instrumentation.ActivityMonitor = instrumentation.addMonitor(null as IntentFilter?, null, true)
        try {
            instrumentation.runOnMainSync { WidgetActionReceiver().onReceive(context, WidgetIntents.noopIntent(context)) }
            instrumentation.waitForIdleSync()
            assertEquals(0, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    /** The error banner still opens setup for token problems, and retries for everything else. */
    @Test fun errorBannerTargetsSetupOnlyForTokenProblems() {
        val views = WidgetViews(context)
        val setup = WidgetIntents.setup(context)
        val refresh = WidgetIntents.refresh(context)
        setupErrors.forEach { assertEquals("$it", setup, views.errorTap(it)) }
        retryErrors.forEach { assertEquals("$it", refresh, views.errorTap(it)) }
        (setupErrors + retryErrors).forEach {
            assertTrue("$it banner clickable", widget(WidgetState(FakeBoards.realistic(), it, FakeBoards.NOW)).clickable(R.id.error))
        }
    }
}
