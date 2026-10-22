package io.github.veganmarshmallow.taskboard

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.TabTestViews.configured
import io.github.veganmarshmallow.taskboard.TabTestViews.isHighlighted
import io.github.veganmarshmallow.taskboard.TabTestViews.shownTab
import io.github.veganmarshmallow.taskboard.TabTestViews.tabSlot
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.board.TabSelection
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.data.TabStore
import io.github.veganmarshmallow.taskboard.widget.BoardWidgetProvider
import io.github.veganmarshmallow.taskboard.widget.TabTap
import io.github.veganmarshmallow.taskboard.widget.WidgetActionReceiver
import io.github.veganmarshmallow.taskboard.widget.WidgetIntents
import io.github.veganmarshmallow.taskboard.widget.WidgetViews
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The tab row on a device: labels, highlight, tap intents, and the per-widget memory. */
@RunWith(AndroidJUnit4::class)
class TabsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val realistic = WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW)

    @After fun cleanUp() = TabStore(context).clear(intArrayOf(41, 42))

    private fun apply(state: WidgetState, tab: Section, widgetId: Int = 41, build: Context = context, host: Context = context): View {
        var view: View? = null
        instrumentation.runOnMainSync { view = WidgetViews(build).build(state, widgetId, tab).apply(host, FrameLayout(host)) }
        return view!!
    }

    @Test fun tabsShowShortLabelsWithCounts() {
        val v = apply(realistic, Section.NEEDS_YOU)
        val labels = Section.entries.map { v.shownTab(it).text.toString() }
        // Needs you is exclusive: the blocked question and the PR are counted there only.
        assertEquals(listOf("Needs you 2", "Todo 2", "Doing 2", "Blocked 1", "Review 0", "Done 3"), labels)
        assertEquals("Todo: 2", v.tabSlot(Section.TODO).contentDescription.toString())
        assertEquals("Needs you: 2, selected", v.tabSlot(Section.NEEDS_YOU).contentDescription.toString())
    }

    @Test fun hugeCountsAreCappedOnTheTabs() {
        val v = apply(WidgetState(FakeBoards.busy(), null, FakeBoards.NOW), Section.TODO)
        assertEquals("Needs you 999+", v.shownTab(Section.NEEDS_YOU).text.toString())
        assertEquals("Doing 999", v.shownTab(Section.IN_PROGRESS).text.toString())
        assertEquals("Blocked 0", v.shownTab(Section.BLOCKED).text.toString())
        assertEquals("Needs you: 1500", v.tabSlot(Section.NEEDS_YOU).contentDescription.toString()) // the true count is read out
    }

    @Test fun onlyTheSelectedTabIsHighlighted() {
        Section.entries.forEach { selected ->
            val v = apply(realistic, selected)
            Section.entries.forEach { assertEquals("$selected/$it", it == selected, v.isHighlighted(it)) }
            assertTrue(v.tabSlot(selected).contentDescription.toString().endsWith(context.getString(R.string.tab_selected)))
        }
    }

    @Test fun tabColoursFollowTheLaunchersThemeNotTheOneTheyWereBuiltIn() {
        // Built while the app was in light mode, shown after the user switched to dark (and back).
        val light = configured(context, night = false)
        val dark = configured(context, night = true)
        listOf(light to dark, dark to light).forEach { (builtIn, shownIn) ->
            val v = apply(realistic, Section.TODO, build = builtIn, host = shownIn)
            assertEquals(shownIn.getColor(R.color.widget_accent_text), v.shownTab(Section.TODO).currentTextColor)
            assertEquals(shownIn.getColor(R.color.widget_header_text), v.shownTab(Section.DONE).currentTextColor)
        }
        assertNotEquals(light.getColor(R.color.widget_header_text), dark.getColor(R.color.widget_header_text))
    }

    @Test fun withoutABoardTabsShowNamesOnly() {
        val v = apply(WidgetState.INITIAL, Section.TODO)
        assertEquals("Todo", v.shownTab(Section.TODO).text.toString())
        assertEquals("In progress", v.tabSlot(Section.IN_PROGRESS).contentDescription.toString()) // no "0" before a load
    }

    @Test fun twoWidgetsShowTheirOwnTab() {
        val first = apply(realistic, Section.DONE, widgetId = 41)
        val second = apply(realistic, Section.TODO, widgetId = 42)
        assertTrue(first.isHighlighted(Section.DONE))
        assertFalse(first.isHighlighted(Section.TODO))
        assertTrue(second.isHighlighted(Section.TODO))
        // Each widget gets its own list factory (it reads that widget's tab).
        assertNotEquals(WidgetIntents.listService(context, 41).data, WidgetIntents.listService(context, 42).data)
    }

    @Test fun tabTapIntentsAreDistinctPerWidgetAndTab() {
        val intents = listOf(41, 42).flatMap { id -> Section.entries.map { WidgetIntents.selectTabIntent(context, id, it) } }
        assertEquals(intents.size, intents.map { it.data }.toSet().size)
        val first = intents.first()
        assertEquals(WidgetIntents.ACTION_SELECT_TAB, first.action)
        assertEquals(TabSelection.key(Section.NEEDS_YOU), first.getStringExtra(WidgetIntents.EXTRA_TAB))
        assertNotEquals(WidgetIntents.selectTab(context, 41, Section.TODO), WidgetIntents.selectTab(context, 42, Section.TODO))
    }

    @Test fun aTabTapThroughTheReceiverStoresThePickForThatWidgetOnly() {
        instrumentation.runOnMainSync {
            WidgetActionReceiver().onReceive(context, WidgetIntents.selectTabIntent(context, 41, Section.BLOCKED))
        }
        val board = FakeBoards.realistic()
        assertEquals(Section.BLOCKED, TabStore(context).selected(41, board))
        assertEquals(Section.NEEDS_YOU, TabStore(context).selected(42, board))
    }

    @Test fun malformedTabTapsChangeNothing() {
        TabStore(context).select(41, Section.DONE)
        val good = WidgetIntents.selectTabIntent(context, 41, Section.TODO)
        listOf(
            Intent(good).apply { removeExtra(AppWidgetManager.EXTRA_APPWIDGET_ID) },
            Intent(good).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
            Intent(good).apply { removeExtra(WidgetIntents.EXTRA_TAB) },
            Intent(good).putExtra(WidgetIntents.EXTRA_TAB, "ARCHIVED"),
            Intent(good).putExtra(WidgetIntents.EXTRA_TAB, "todo"),
        ).forEach { intent ->
            var handled = true
            instrumentation.runOnMainSync { handled = TabTap.handle(context, intent) }
            assertFalse("$intent", handled)
        }
        assertEquals(Section.DONE, TabStore(context).selected(41, FakeBoards.realistic()))
    }

    @Test fun pickIsRememberedPerWidgetAndClearedOnDelete() {
        val board = FakeBoards.realistic() // has Needs you items
        val store = TabStore(context)
        assertEquals(Section.NEEDS_YOU, store.selected(41, board))
        store.select(41, Section.DONE)
        assertEquals(Section.DONE, TabStore(context).selected(41, board)) // new instance: survives process death
        assertEquals(Section.NEEDS_YOU, store.selected(42, board)) // other widget unaffected
        store.clear(intArrayOf(41))
        assertEquals(Section.NEEDS_YOU, store.selected(41, board))
        assertEquals(Section.TODO, store.selected(41, FakeBoards.emptyWithDone()))
    }

    @Test fun removingAWidgetForgetsItsTabSoAReAddedOneStartsOnTheDefault() {
        val store = TabStore(context)
        store.select(41, Section.DONE)
        store.select(42, Section.BLOCKED)
        BoardWidgetProvider().onDeleted(context, intArrayOf(41))
        assertEquals(Section.NEEDS_YOU, store.selected(41, FakeBoards.realistic()))
        assertEquals(Section.BLOCKED, store.selected(42, FakeBoards.realistic()))
        BoardWidgetProvider().onDeleted(context, intArrayOf()) // nothing to forget: no crash
    }

    @Test fun anEmptySelectedTabShowsItsEmptyMessageAndStaysSelected() {
        TabStore(context).select(41, Section.BLOCKED)
        val board = FakeBoards.emptyWithDone()
        assertEquals(Section.BLOCKED, TabStore(context).selected(41, board))
        val v = apply(WidgetState(board, null, FakeBoards.NOW), Section.BLOCKED)
        assertTrue(v.isHighlighted(Section.BLOCKED))
        assertEquals("Blocked 0", v.shownTab(Section.BLOCKED).text.toString())
    }
}
