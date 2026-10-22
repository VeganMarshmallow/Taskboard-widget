package io.github.veganmarshmallow.taskboard

import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.widget.WidgetViews
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
    )

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
}
