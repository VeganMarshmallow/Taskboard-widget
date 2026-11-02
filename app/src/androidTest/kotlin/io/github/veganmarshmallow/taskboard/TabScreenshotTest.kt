package io.github.veganmarshmallow.taskboard

import android.appwidget.AppWidgetManager
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.TabTestViews.assertTabsFit
import io.github.veganmarshmallow.taskboard.TabTestViews.configured
import io.github.veganmarshmallow.taskboard.TabTestViews.shownTab
import io.github.veganmarshmallow.taskboard.TabTestViews.tabFitProblems
import io.github.veganmarshmallow.taskboard.TabTestViews.tabSlot
import io.github.veganmarshmallow.taskboard.TabTestViews.topIn
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** #10: the status tabs — each tab, the narrowest and smallest sizes, big fonts, dark mode and RTL. */
@Screenshot
@RunWith(AndroidJUnit4::class)
class TabScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val board = WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW)
    private val busy = WidgetState(FakeBoards.busy(), null, FakeBoards.NOW)

    private fun View.tabText(section: Section) = shownTab(section).text.toString()
    private fun View.rowCount() = findViewById<ViewGroup>(R.id.board_list).childCount

    @Test fun tabNeedsYouIsTheDefault() {
        val widget = ScreenshotCapture.widget("10-1-tab-needs-you-default", board)
        assertEquals("Needs you 1", widget.tabText(Section.NEEDS_YOU))
        assertEquals(1, widget.rowCount())
    }

    @Test fun tabTodo() {
        val widget = ScreenshotCapture.widget("10-2-tab-todo", board, tab = Section.TODO)
        assertEquals(2, widget.rowCount())
    }

    @Test fun tabInProgressWithHebrewTitle() {
        val widget = ScreenshotCapture.widget("10-3-tab-in-progress", board, tab = Section.IN_PROGRESS)
        assertEquals(2, widget.rowCount())
    }

    @Test fun tabDoneListsLastSevenDays() {
        val widget = ScreenshotCapture.widget("10-4-tab-done", board, tab = Section.DONE)
        assertEquals("Done 3", widget.tabText(Section.DONE))
        assertEquals(3, widget.rowCount())
    }

    @Test fun tabEmptyState() {
        val empty = WidgetState(FakeBoards.emptyWithDone(), null, FakeBoards.NOW)
        val widget = ScreenshotCapture.widget("10-5-tab-blocked-empty", empty, heightDp = 220, tab = Section.BLOCKED)
        assertEquals(1, widget.rowCount())
    }

    @Test fun tabsOnTheNarrowestWidget() {
        // 180dp is the widget's minResizeWidth: the narrowest the launcher allows.
        val widget = ScreenshotCapture.widget("10-6-tabs-narrow-180dp", board, widthDp = 180, heightDp = 300, tab = Section.TODO)
        assertTabsFit(widget, "180dp")
    }

    @Test fun tabsFitAtEveryFontScaleWithHugeCounts() {
        val problems = listOf(1f, 1.15f, 1.3f, 1.5f, 1.75f, 2f).flatMap { scale ->
            val host = configured(context, fontScale = scale)
            listOf(180, 250, 340).flatMap { width ->
                listOf("realistic" to board, "busy" to busy).flatMap { (name, state) ->
                    lateinit var widget: View
                    instrumentation.runOnMainSync { widget = WidgetHost(host).render(state, width, 300, Section.TODO).widget }
                    tabFitProblems(widget, "x$scale ${width}dp $name")
                }
            }
        }
        assertTrue(problems.joinToString("; "), problems.isEmpty())
        // The pictures: the hardest cases.
        val big = configured(context, fontScale = 1.3f)
        val huge = configured(context, fontScale = 2f)
        assertTabsFit(ScreenshotCapture.widget("10-7-tabs-180dp-font-1.3-huge-counts", busy, widthDp = 180, tab = Section.NEEDS_YOU, host = big), "shot 1.3")
        assertTabsFit(ScreenshotCapture.widget("10-8-tabs-180dp-font-2.0-huge-counts", busy, widthDp = 180, tab = Section.NEEDS_YOU, host = huge), "shot 2.0")
    }

    @Test fun tabsInDarkMode() {
        val dark = configured(context, night = true)
        val widget = ScreenshotCapture.widget("10-9-tabs-dark", board, host = dark)
        assertEquals(dark.getColor(R.color.widget_accent_text), widget.shownTab(Section.NEEDS_YOU).currentTextColor)
        assertEquals(dark.getColor(R.color.widget_header_text), widget.shownTab(Section.TODO).currentTextColor)
    }

    @Test fun tabsInAnRtlLocale() {
        val hebrew = configured(context, locale = Locale("he"))
        val widget = ScreenshotCapture.widget("10-10-tabs-rtl-hebrew", board, tab = Section.IN_PROGRESS, host = hebrew)
        // Mirrored: the first tab sits on the right.
        assertTrue(widget.tabSlot(Section.NEEDS_YOU).left > widget.tabSlot(Section.IN_PROGRESS).left)
        assertTrue(widget.tabSlot(Section.BLOCKED).left > widget.tabSlot(Section.DONE).left)
        assertTabsFit(widget, "rtl")
    }

    @Test fun smallestAllowedSizeStillShowsTabsAndOneRow() {
        val info = AppWidgetManager.getInstance(context).getInstalledProvidersForPackage(context.packageName, null).single()
        val density = context.resources.displayMetrics.density
        val widthDp = (info.minResizeWidth / density).toInt()
        val heightDp = (info.minResizeHeight / density).toInt()
        val widget = ScreenshotCapture.widget("10-11-smallest-size", board, widthDp = widthDp, heightDp = heightDp, tab = Section.TODO)
        val list = widget.findViewById<View>(R.id.board_list)
        val tabs = widget.findViewById<View>(R.id.tabs)
        val tabsBottom = tabs.topIn(widget) + tabs.height
        assertTrue("tabs end at $tabsBottom, list starts at ${list.topIn(widget)}", tabsBottom <= list.topIn(widget))
        assertTrue("list runs past the widget", list.topIn(widget) + list.height <= widget.height)
        assertTrue("list is ${list.height / density}dp at ${widthDp}x${heightDp}dp", list.height >= 36 * density)
        assertTabsFit(widget, "smallest")
    }
}
