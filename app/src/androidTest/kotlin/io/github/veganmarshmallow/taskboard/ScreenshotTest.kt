package io.github.veganmarshmallow.taskboard

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.TabTestViews.configured
import io.github.veganmarshmallow.taskboard.TabTestViews.shownTab
import io.github.veganmarshmallow.taskboard.TabTestViews.tabSlot
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.board.TabSelection
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.setup.SetupActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Captures the PNGs embedded in the PR. Each test also checks the view shows what the picture claims. */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun capture(
        name: String,
        state: WidgetState,
        widthDp: Int = 340,
        heightDp: Int = 300,
        tab: Section = TabSelection.default(state.board),
        host: Context = context,
    ): View {
        lateinit var widget: View
        instrumentation.runOnMainSync {
            val rendered = WidgetHost(host).render(state, widthDp, heightDp, tab)
            widget = rendered.widget
            Screenshots.save(context, name, WidgetHost.toBitmap(rendered.root))
        }
        return widget
    }

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun View.visible(id: Int) = findViewById<View>(id).visibility == View.VISIBLE

    @Test fun setupScreen() {
        TokenStore(context).clear()
        val intent = Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity: Activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        var bitmap: Bitmap? = null
        instrumentation.runOnMainSync {
            val root = activity.window.decorView
            assertTrue(root.findViewById<View>(R.id.test).isShown)
            bitmap = WidgetHost.toBitmap(root)
        }
        Screenshots.save(context, "1-setup", bitmap!!)
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test fun newTaskForm() {
        val intent = Intent(context, io.github.veganmarshmallow.taskboard.newtask.NewTaskActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        io.github.veganmarshmallow.taskboard.newtask.NewTaskController.session.draft =
            io.github.veganmarshmallow.taskboard.core.task.TaskDraft(
                title = "Weekly digest email of finished tasks",
                goal = "Every Sunday, email a summary of tasks done that week.\n- Group by repo\n- Link each PR",
                doneWhen = "A test email arrives with last week's tasks",
                repo = "VeganMarshmallow/digest",
                approveNow = true,
            )
        val activity: Activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        var bitmap: Bitmap? = null
        instrumentation.runOnMainSync {
            val root = activity.window.decorView
            assertTrue(root.findViewById<View>(R.id.task_submit).isShown)
            bitmap = WidgetHost.toBitmap(root)
        }
        Screenshots.save(context, "9-new-task-form", bitmap!!)
        instrumentation.runOnMainSync { activity.finish() }
        io.github.veganmarshmallow.taskboard.newtask.NewTaskController.session.draft =
            io.github.veganmarshmallow.taskboard.core.task.TaskDraft.EMPTY
    }

    @Test fun widgetWithBoard() {
        val widget = capture("2-widget-board", WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW), heightDp = 470)
        assertTrue(widget.visible(R.id.board_list))
        assertTrue(!widget.visible(R.id.error))
    }

    @Test fun widgetErrorNoToken() {
        val widget = capture("3-widget-error-no-token", WidgetState(null, BoardError.NoToken, FakeBoards.NOW))
        assertTrue(widget.visible(R.id.error))
        assertTrue(widget.text(R.id.error).endsWith("tap to set up"))
        assertTrue(!widget.visible(R.id.board_list))
    }

    @Test fun widgetStaleBoardUnderRateLimit() {
        val state = WidgetState(FakeBoards.realistic(), BoardError.RateLimited(FakeBoards.NOW.plusSeconds(1500)), FakeBoards.NOW)
        val widget = capture("5-widget-stale-rate-limited", state)
        assertTrue(widget.visible(R.id.error))
        assertTrue(widget.visible(R.id.board_list))
    }

    @Test fun widgetEmptyBoard() {
        val widget = capture("4-widget-empty", WidgetState(FakeBoards.emptyWithDone(), null, FakeBoards.NOW), heightDp = 200)
        assertTrue(widget.visible(R.id.board_list))
        assertEquals(View.GONE, widget.findViewById<View>(R.id.error).visibility)
    }

    // --- #10 status tabs -------------------------------------------------------------------

    private val board = WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW)
    private val busy = WidgetState(FakeBoards.busy(), null, FakeBoards.NOW)
    private fun View.tabText(section: Section) = shownTab(section).text.toString()
    private fun View.topIn(root: View): Int {
        var y = 0
        var v: View = this
        while (v !== root) { y += v.top; v = v.parent as View }
        return y
    }
    private fun View.rowCount() = findViewById<android.view.ViewGroup>(R.id.board_list).childCount

    /** Every tab label fits its tab: at most two lines, broken between words, nothing cut off, not below 8dp. */
    private fun tabFitProblems(widget: View, what: String): List<String> {
        val density = widget.resources.displayMetrics.density
        return Section.entries.flatMap { section ->
            val tab = widget.shownTab(section)
            val layout = tab.layout
            val name = "$what $section '${tab.text}' @${"%.1f".format(tab.textSize / density)}dp"
            val problems = mutableListOf<String>()
            if (layout.lineCount > 2) problems += "$name: ${layout.lineCount} lines"
            if ((0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 }) problems += "$name: cut off"
            if ((0 until layout.lineCount - 1).any { tab.text[layout.getLineEnd(it) - 1] != ' ' }) problems += "$name: breaks inside a word"
            if (layout.height > tab.height - tab.paddingTop - tab.paddingBottom) problems += "$name: taller than the tab"
            if (tab.textSize < 8 * density - 0.5f) problems += "$name: below 8dp"
            problems
        }
    }

    private fun assertTabsFit(widget: View, what: String) {
        val problems = tabFitProblems(widget, what)
        assertTrue(problems.joinToString("; "), problems.isEmpty())
    }

    @Test fun tabNeedsYouIsTheDefault() {
        val widget = capture("10-1-tab-needs-you-default", board)
        assertEquals("Needs you 2", widget.tabText(Section.NEEDS_YOU))
        assertEquals(2, widget.rowCount())
    }

    @Test fun tabTodo() {
        val widget = capture("10-2-tab-todo", board, tab = Section.TODO)
        assertEquals(2, widget.rowCount())
    }

    @Test fun tabInProgressWithHebrewTitle() {
        val widget = capture("10-3-tab-in-progress", board, tab = Section.IN_PROGRESS)
        assertEquals(2, widget.rowCount())
    }

    @Test fun tabDoneListsLastSevenDays() {
        val widget = capture("10-4-tab-done", board, tab = Section.DONE)
        assertEquals("Done 3", widget.tabText(Section.DONE))
        assertEquals(3, widget.rowCount())
    }

    @Test fun tabEmptyState() {
        val widget = capture("10-5-tab-blocked-empty", WidgetState(FakeBoards.emptyWithDone(), null, FakeBoards.NOW), heightDp = 220, tab = Section.BLOCKED)
        assertEquals(1, widget.rowCount())
    }

    @Test fun tabsOnTheNarrowestWidget() {
        // 180dp is the widget's minResizeWidth: the narrowest the launcher allows.
        val widget = capture("10-6-tabs-narrow-180dp", board, widthDp = 180, heightDp = 300, tab = Section.TODO)
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
        assertTabsFit(capture("10-7-tabs-180dp-font-1.3-huge-counts", busy, widthDp = 180, tab = Section.NEEDS_YOU, host = configured(context, fontScale = 1.3f)), "shot 1.3")
        assertTabsFit(capture("10-8-tabs-180dp-font-2.0-huge-counts", busy, widthDp = 180, tab = Section.NEEDS_YOU, host = configured(context, fontScale = 2f)), "shot 2.0")
    }

    @Test fun tabsInDarkMode() {
        val dark = configured(context, night = true)
        val widget = capture("10-9-tabs-dark", board, host = dark)
        assertEquals(dark.getColor(R.color.widget_accent_text), widget.shownTab(Section.NEEDS_YOU).currentTextColor)
        assertEquals(dark.getColor(R.color.widget_header_text), widget.shownTab(Section.TODO).currentTextColor)
    }

    @Test fun tabsInAnRtlLocale() {
        val hebrew = configured(context, locale = Locale("he"))
        val widget = capture("10-10-tabs-rtl-hebrew", board, tab = Section.IN_PROGRESS, host = hebrew)
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
        val widget = capture("10-11-smallest-size", board, widthDp = widthDp, heightDp = heightDp, tab = Section.TODO)
        val list = widget.findViewById<View>(R.id.board_list)
        val tabs = widget.findViewById<View>(R.id.tabs)
        val tabsBottom = tabs.topIn(widget) + tabs.height
        assertTrue("tabs end at $tabsBottom, list starts at ${list.topIn(widget)}", tabsBottom <= list.topIn(widget))
        assertTrue("list runs past the widget", list.topIn(widget) + list.height <= widget.height)
        assertTrue("list is ${list.height / density}dp at ${widthDp}x${heightDp}dp", list.height >= 36 * density)
        assertTabsFit(widget, "smallest")
    }
}
