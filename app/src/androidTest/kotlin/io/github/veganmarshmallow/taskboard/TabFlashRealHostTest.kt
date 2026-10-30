package io.github.veganmarshmallow.taskboard

import android.os.Build
import android.os.SystemClock
import android.widget.ListView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.board.BoardRows
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.data.TabStore
import io.github.veganmarshmallow.taskboard.widget.BoardListFactory
import io.github.veganmarshmallow.taskboard.widget.StateRedraw
import io.github.veganmarshmallow.taskboard.widget.WidgetRenderer
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task #40: after one tap the highlight appeared, then went back to the old tab about a second
 * later. Each test taps a tab on a real widget host and then keeps watching for [WATCH_MS]: the
 * highlight must stay on the tapped tab the whole time, and the list must show that tab's rows.
 *
 * The CI also runs this on a recent Android (see build.yml), where AppWidgetManager converts a
 * RemoteViewsService list itself and re-sends the system's last views on a list change: that is
 * where the old highlight came back from.
 */
@RunWith(AndroidJUnit4::class)
class TabFlashRealHostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val board = FakeBoards.realistic()
    private lateinit var host: RealWidgetHost

    @Before fun setUp() {
        host = RealWidgetHost(instrumentation)
        host.seed(WidgetState(board, null, FakeBoards.NOW))
        host.awaitHighlighted(Section.NEEDS_YOU, "a new widget starts on the default tab")
    }

    @After fun tearDown() {
        TabStore(context).clear(intArrayOf(host.widgetId))
        host.close()
    }

    @Test fun oneTapStaysOnTheTappedTab() {
        listOf(Section.BLOCKED, Section.TODO, Section.DONE, Section.IN_PROGRESS, Section.NEEDS_YOU).forEach { section ->
            host.tap(section)
            watch(section, "tap on $section")
        }
    }

    /** A refresh's draw, the sweep and the update dot's redraw just before and just after the tap. */
    @Test fun oneTapStaysOnTheTappedTabWithRedrawsAroundIt() {
        listOf(Section.TODO, Section.BLOCKED, Section.DONE, Section.NEEDS_YOU).forEach { section ->
            val before = redrawsInBackground()
            host.tap(section)
            val after = redrawsInBackground()
            before.join()
            after.join()
            watch(section, "tap on $section with redraws around it")
        }
    }

    /** What a refresh, the sweep and the update dot each draw, from a background thread like their jobs. */
    private fun redrawsInBackground(): Thread = Thread {
        val renderer = WidgetRenderer(context)
        renderer.render(StateStore(context).load(), intArrayOf(host.widgetId)) // a refresh's result
        renderer.renderStored(intArrayOf(host.widgetId)) // the sweep
        StateRedraw.redraw(context) // the update dot
    }.also(Thread::start)

    /** Waits for the tapped tab, then checks for [WATCH_MS] that the highlight and the list stay on it. */
    private fun watch(section: Section, why: String) {
        host.awaitHighlighted(section, why)
        val expectedRows = BoardRows.of(board, section).map { it.id }
        val end = SystemClock.uptimeMillis() + WATCH_MS
        var listSettled = false
        while (SystemClock.uptimeMillis() < end) {
            val shown = host.highlighted()
            if (shown != setOf(section)) {
                fail("$why: $section was highlighted, then after ${WATCH_MS - (end - SystemClock.uptimeMillis())} ms the host showed $shown")
            }
            val rows = rowIds()
            if (rows == expectedRows) listSettled = true
            else if (listSettled) fail("$why: the list showed $section's rows, then changed to $rows")
            SystemClock.sleep(POLL_MS)
        }
        if (!listSettled) fail("$why: the list never showed $section's rows $expectedRows (last: ${rowIds()})")
    }

    /**
     * The list's row ids. Android 12+ hosts receive the rows inside the widget's update, so the
     * hosted list's adapter is read directly. Older hosts bind a RemoteViewsService asynchronously,
     * so the rows are read through [BoardListFactory], the code that service runs.
     */
    private fun rowIds(): List<Long> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val factory = BoardListFactory(context, host.widgetId).apply { onDataSetChanged() }
            return (0 until factory.count).map(factory::getItemId)
        }
        var ids = emptyList<Long>()
        instrumentation.runOnMainSync {
            val adapter = host.view.findViewById<ListView>(R.id.board_list)?.adapter
            if (adapter != null) ids = (0 until adapter.count).map(adapter::getItemId)
        }
        return ids
    }

    private companion object {
        const val WATCH_MS = 3_000L
        const val POLL_MS = 50L
    }
}
