package io.github.veganmarshmallow.taskboard

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.board.TabSelection
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.data.TabStore
import io.github.veganmarshmallow.taskboard.widget.TabViews
import io.github.veganmarshmallow.taskboard.widget.WidgetIntents
import io.github.veganmarshmallow.taskboard.widget.WidgetViews
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The tab row on a device: labels, highlight, tap intents, and the per-widget memory. */
@RunWith(AndroidJUnit4::class)
class TabsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @After fun cleanUp() = TabStore(context).clear(intArrayOf(41, 42))

    private fun apply(state: WidgetState, tab: Section): View {
        var view: View? = null
        instrumentation.runOnMainSync { view = WidgetViews(context).build(state, 41, tab).apply(context, FrameLayout(context)) }
        return view!!
    }

    private fun View.tab(section: Section) = findViewById<TextView>(TabViews.viewId(section))

    @Test fun tabsShowShortLabelsWithCounts() {
        val v = apply(WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW), Section.NEEDS_YOU)
        val labels = Section.entries.map { v.tab(it).text.toString() }
        assertEquals(listOf("Needs you 2", "Todo 2", "Doing 2", "Blocked 2", "Review 1", "Done 3"), labels)
        Section.entries.forEach { assertEquals(View.VISIBLE, v.tab(it).visibility) }
        assertTrue(v.tab(Section.TODO).contentDescription.toString().startsWith("Todo: 2"))
    }

    @Test fun onlyTheSelectedTabIsHighlighted() {
        Section.entries.forEach { selected ->
            val v = apply(WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW), selected)
            val highlighted = context.getColor(R.color.widget_accent_text)
            Section.entries.forEach {
                val isHighlighted = v.tab(it).currentTextColor == highlighted
                assertEquals("$selected/$it", it == selected, isHighlighted)
            }
            assertTrue(v.tab(selected).contentDescription.toString().endsWith(context.getString(R.string.tab_selected)))
        }
    }

    @Test fun withoutABoardTabsShowNamesOnly() {
        val v = apply(WidgetState.INITIAL, Section.TODO)
        assertEquals("Todo", v.tab(Section.TODO).text.toString())
    }

    @Test fun tabTapIntentsAreDistinctPerWidgetAndTab() {
        val intents = listOf(41, 42).flatMap { id -> Section.entries.map { WidgetIntents.selectTabIntent(context, id, it) } }
        assertEquals(intents.size, intents.map { it.data }.toSet().size)
        val first = intents.first()
        assertEquals(WidgetIntents.ACTION_SELECT_TAB, first.action)
        assertEquals(TabSelection.key(Section.NEEDS_YOU), first.getStringExtra(WidgetIntents.EXTRA_TAB))
        assertNotEquals(WidgetIntents.selectTab(context, 41, Section.TODO), WidgetIntents.selectTab(context, 42, Section.TODO))
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
}
