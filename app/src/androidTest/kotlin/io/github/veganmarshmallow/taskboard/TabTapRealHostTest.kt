package io.github.veganmarshmallow.taskboard

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.data.TabStore
import io.github.veganmarshmallow.taskboard.widget.BoardListFactory
import io.github.veganmarshmallow.taskboard.widget.WidgetRenderer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Task #33: one tap on a tab must update the highlight and the list together. Each tap here is a
 * real click on a widget hosted the way a launcher hosts it ([RealWidgetHost]), so it goes through
 * the PendingIntent, WidgetActionReceiver, TabTap, AppWidgetManager and system_server, and the
 * assertion reads the views the host actually shows.
 */
@RunWith(AndroidJUnit4::class)
class TabTapRealHostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val board = FakeBoards.realistic()
    private lateinit var host: RealWidgetHost

    @Before fun setUp() {
        StateStore(context).save(WidgetState(board, null, FakeBoards.NOW))
        host = RealWidgetHost(instrumentation)
        host.awaitHighlighted(Section.NEEDS_YOU, "a new widget starts on the default tab")
    }

    @After fun tearDown() {
        TabStore(context).clear(intArrayOf(host.widgetId))
        host.close()
    }

    /** One tap, then: the host highlights that tab only, it stays so, and the list shows that tab's rows. */
    private fun tapOnce(section: Section, why: String) {
        host.tap(section)
        host.awaitHighlighted(section, why)
        host.assertStaysHighlighted(section, why)
        assertTrue(why, host.slotDescription(section).endsWith(context.getString(R.string.tab_selected)))
        assertEquals(why, section, TabStore(context).selected(host.widgetId, board))
        val list = BoardListFactory(context, host.widgetId).apply { onDataSetChanged() }
        assertEquals("$why: list rows", board.count(section), list.getCount())
    }

    private fun awaitPick(section: Section) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (TabStore(context).selected(host.widgetId, board) != section) {
            if (SystemClock.uptimeMillis() > deadline) fail("the tap on $section never reached the receiver")
            SystemClock.sleep(5)
        }
    }

    @Test fun oneTapHighlightsTheTappedTabAndShowsItsList() {
        tapOnce(Section.BLOCKED, "first tap")
    }

    @Test fun everyTabTakesExactlyOneTapSeveralTimesInARow() {
        val order = listOf(Section.TODO, Section.IN_PROGRESS, Section.BLOCKED, Section.DONE, Section.NEEDS_YOU)
        repeat(3) { round -> order.forEach { tapOnce(it, "round $round, tap on $it") } }
    }

    @Test fun tappingTheSelectedTabAgainKeepsItHighlighted() {
        tapOnce(Section.DONE, "first tap")
        tapOnce(Section.DONE, "same tab again")
        tapOnce(Section.TODO, "then another tab")
    }

    @Test fun tapsInQuickSuccessionEndOnTheLastOne() {
        val bursts = listOf(
            listOf(Section.TODO, Section.BLOCKED, Section.DONE),
            listOf(Section.IN_PROGRESS, Section.NEEDS_YOU, Section.TODO, Section.BLOCKED, Section.IN_PROGRESS),
            listOf(Section.DONE, Section.DONE, Section.NEEDS_YOU),
        )
        bursts.forEachIndexed { i, burst ->
            burst.forEach(host::tap) // back to back, no waiting in between
            val last = burst.last()
            host.awaitHighlighted(last, "burst $i")
            host.assertStaysHighlighted(last, "burst $i")
            assertEquals("burst $i", last, TabStore(context).selected(host.widgetId, board))
        }
    }

    /**
     * A redraw on another thread (a refresh finishing, the sweep, the update dot) while the user taps.
     * Whatever order they land in, the host must end on the tab that was tapped last.
     */
    @Test fun aRedrawRacingTheTapNeverLeavesTheOldHighlight() {
        val tabs = listOf(Section.TODO, Section.BLOCKED, Section.DONE, Section.IN_PROGRESS, Section.NEEDS_YOU)
        repeat(4) { round ->
            tabs.forEach { section ->
                val running = AtomicBoolean(true)
                val redraws = Thread {
                    val renderer = WidgetRenderer(context)
                    val store = StateStore(context)
                    while (running.get()) {
                        renderer.render(store.load(), intArrayOf(host.widgetId), updating = false)
                        Thread.sleep(1)
                    }
                }
                redraws.start()
                host.tap(section)
                awaitPick(section) // the tap's broadcast reached TabTap (it is delivered asynchronously)
                instrumentation.waitForIdleSync() // and TabTap's own render has finished on the main thread
                running.set(false)
                redraws.join()
                host.awaitHighlighted(section, "round $round, tap on $section with redraws racing")
                host.assertStaysHighlighted(section, "round $round, tap on $section with redraws racing")
            }
        }
    }
}
