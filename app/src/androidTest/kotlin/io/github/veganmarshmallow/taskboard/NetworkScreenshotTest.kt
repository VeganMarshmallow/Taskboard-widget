package io.github.veganmarshmallow.taskboard

import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** #29: "No network" only when the device has none; a network that can't reach GitHub says so. */
@RunWith(AndroidJUnit4::class)
class NetworkScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun capture(name: String, error: BoardError): View {
        lateinit var widget: View
        instrumentation.runOnMainSync {
            val rendered = WidgetHost(context).render(WidgetState(FakeBoards.realistic(), error, FakeBoards.NOW), 340, 300)
            widget = rendered.widget
            Screenshots.save(context, name, WidgetHost.toBitmap(rendered.root))
        }
        return widget
    }

    private fun View.errorText() = findViewById<TextView>(R.id.error).text.toString()

    @Test fun cantReachGitHubOnAWorkingNetwork() {
        val widget = capture("29-widget-cant-reach-github", BoardError.Unreachable)
        assertTrue(widget.findViewById<View>(R.id.error).visibility == View.VISIBLE)
        assertTrue(widget.errorText().startsWith("Can't reach GitHub"))
        assertTrue(widget.errorText().endsWith("tap to retry"))
        assertTrue("the last good board stays", widget.findViewById<View>(R.id.board_list).visibility == View.VISIBLE)
    }

    @Test fun noNetworkWhenTheDeviceIsOffline() {
        val widget = capture("29-widget-no-network", BoardError.Network)
        assertTrue(widget.errorText().startsWith("No network"))
        assertTrue(widget.errorText().endsWith("tap to retry"))
    }
}
