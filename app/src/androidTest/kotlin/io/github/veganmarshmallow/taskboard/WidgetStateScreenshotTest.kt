package io.github.veganmarshmallow.taskboard

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.setup.SetupActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The first-run pictures: the setup screen and the widget's board, empty, error and stale states (incl. #17). */
@Screenshot
@RunWith(AndroidJUnit4::class)
class WidgetStateScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun View.visible(id: Int) = findViewById<View>(id).visibility == View.VISIBLE

    @Test fun setupScreen() {
        TokenStore(context).clear()
        val intent = Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity: Activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        ScreenshotCapture.activity("1-setup", activity) { root -> assertTrue(root.findViewById<View>(R.id.test).isShown) }
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test fun widgetWithBoard() {
        val widget = ScreenshotCapture.widget("2-widget-board", WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW), heightDp = 470)
        assertTrue(widget.visible(R.id.board_list))
        assertTrue(!widget.visible(R.id.error))
    }

    @Test fun widgetErrorNoToken() {
        val widget = ScreenshotCapture.widget("3-widget-error-no-token", WidgetState(null, BoardError.NoToken, FakeBoards.NOW))
        assertTrue(widget.visible(R.id.error))
        assertTrue(widget.text(R.id.error).endsWith("tap to set up"))
        assertTrue(!widget.visible(R.id.board_list))
    }

    @Test fun widgetStaleBoardUnderRateLimit() {
        val state = WidgetState(FakeBoards.realistic(), BoardError.RateLimited(FakeBoards.NOW.plusSeconds(1500)), FakeBoards.NOW)
        val widget = ScreenshotCapture.widget("5-widget-stale-rate-limited", state)
        assertTrue(widget.visible(R.id.error))
        assertTrue(widget.visible(R.id.board_list))
    }

    @Test fun widgetEmptyBoard() {
        val widget = ScreenshotCapture.widget("4-widget-empty", WidgetState(FakeBoards.emptyWithDone(), null, FakeBoards.NOW), heightDp = 200)
        assertTrue(widget.visible(R.id.board_list))
        assertEquals(View.GONE, widget.findViewById<View>(R.id.error).visibility)
    }

    /** #17: a refresh that hung (or died with its process) ends in this instead of "updating…" forever. */
    @Test fun widgetStaleBoardAfterRefreshTimeout() {
        val state = WidgetState(FakeBoards.realistic(), BoardError.Timeout, FakeBoards.NOW)
        val widget = ScreenshotCapture.widget("17-widget-refresh-timed-out", state)
        assertTrue(widget.visible(R.id.error))
        assertTrue(widget.text(R.id.error).contains("too long"))
        assertTrue(widget.text(R.id.error).endsWith("tap to retry"))
        assertTrue(widget.visible(R.id.board_list))
        assertTrue(widget.text(R.id.updated).startsWith("last updated "))
    }
}
