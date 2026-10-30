package io.github.veganmarshmallow.taskboard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.data.TabStore
import org.junit.Test
import org.junit.runner.RunWith

/** #33 screenshots: the widget as a real host draws it, before and after one tap on Blocked. */
@RunWith(AndroidJUnit4::class)
class TabTapScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun oneTapOnARealHost() {
        RealWidgetHost(instrumentation).use { host ->
            try {
                host.seed(WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW))
                host.awaitHighlighted(Section.NEEDS_YOU, "default tab")
                Screenshots.save(context, "33-real-host-before-tap", host.headerBitmap())
                host.tap(Section.BLOCKED)
                host.awaitHighlighted(Section.BLOCKED, "one tap")
                host.assertStaysHighlighted(Section.BLOCKED, "one tap")
                Screenshots.save(context, "33-real-host-after-one-tap", host.headerBitmap())
            } finally {
                TabStore(context).clear(intArrayOf(host.widgetId))
            }
        }
    }
}
