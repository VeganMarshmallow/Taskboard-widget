package io.github.veganmarshmallow.taskboard

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.filter.BoardFilter
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.model.Repo
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.data.FilterStore
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.filter.FilterActivity
import io.github.veganmarshmallow.taskboard.widget.TabText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** #59: the filter sheet, empty and with a filter on, and the widget while a filter is on. */
@Screenshot
@RunWith(AndroidJUnit4::class)
class FilterScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val state = WidgetState.INITIAL.withBoard(board())

    @Before @After fun reset() {
        FilterStore(context).clear()
        StateStore(context).save(WidgetState.INITIAL)
    }

    @Test fun theSheetOffersWhatIsOnTheBoard() {
        StateStore(context).save(state)
        val activity = open()
        ScreenshotCapture.activity("59-1-filter-open", activity) { root ->
            assertEquals(listOf("site", "taskboard-widget"), chips(root, R.id.filter_repos).map { it.text.toString() })
            assertEquals(listOf("Feature", "Bug", "Refactor", "Research"), chips(root, R.id.filter_types).map { it.text.toString() })
            assertEquals(
                listOf("High priority", "Normal priority", "Low priority"),
                chips(root, R.id.filter_priorities).map { it.text.toString() },
            )
            assertEquals(emptyList<String>(), picked(root))
            assertEquals(View.INVISIBLE, root.findViewById<View>(R.id.filter_clear).visibility)
            assertEquals("Showing 6 of 6 open tasks", text(root, R.id.filter_summary))
        }
        close(activity)
    }

    @Test fun aFilterOnShowsInTheSheetAndChangesApplyAtOnce() {
        StateStore(context).save(state)
        FilterStore(context).save(BUGS_IN_WIDGET)
        val activity = open()
        ScreenshotCapture.activity("59-2-filter-active", activity) { root ->
            assertEquals(listOf("taskboard-widget", "Bug"), picked(root))
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.filter_clear).visibility)
            assertEquals("Showing 2 of 6 open tasks", text(root, R.id.filter_summary))
        }
        instrumentation.runOnMainSync {
            chips(activity.window.decorView, R.id.filter_types).single { it.text.toString() == "Bug" }.performClick()
        }
        instrumentation.runOnMainSync {
            val root = activity.window.decorView
            assertEquals(listOf("taskboard-widget"), picked(root))
            assertEquals("Showing 4 of 6 open tasks", text(root, R.id.filter_summary))
        }
        close(activity)
        awaitSaved(BoardFilter(repos = setOf(WIDGET.fullName)))
    }

    @Test fun clearTurnsTheFilterOff() {
        StateStore(context).save(state)
        FilterStore(context).save(BUGS_IN_WIDGET.copy(text = "crash"))
        val activity = open()
        instrumentation.runOnMainSync {
            val root = activity.window.decorView
            assertEquals("crash", text(root, R.id.filter_search))
            root.findViewById<View>(R.id.filter_clear).performClick()
            assertEquals(emptyList<String>(), picked(root))
            assertEquals("", text(root, R.id.filter_search))
            assertEquals("Showing 6 of 6 open tasks", text(root, R.id.filter_summary))
        }
        close(activity)
        awaitSaved(BoardFilter.NONE)
    }

    @Test fun theWidgetSaysAFilterIsOnAndListsOnlyMatches() {
        val widget = ScreenshotCapture.widget("59-3-widget-filtered", state, tab = Section.TODO, filter = BUGS_IN_WIDGET)
        assertEquals(View.VISIBLE, widget.findViewById<View>(R.id.filter_bar).visibility)
        assertEquals("Filtered: taskboard-widget, Bug", text(widget, R.id.filter_bar_text))
        assertEquals("Filter, 2 on", widget.findViewById<View>(R.id.filter).contentDescription.toString())
        assertEquals(TabText.label(context, Section.TODO, 1), text(widget, R.id.tab_todo_selected))
        assertEquals(TabText.label(context, Section.NEEDS_YOU, 1), text(widget, R.id.tab_needs_you_label))
        assertEquals(listOf("Fix the crash when the list is empty"), rowTexts(widget))

        val empty = ScreenshotCapture.widget("59-4-widget-filtered-empty-tab", state, tab = Section.IN_PROGRESS, filter = BUGS_IN_WIDGET)
        assertEquals(listOf(context.getString(R.string.empty_filtered)), noteTexts(empty))

        val off = ScreenshotCapture.widget("59-5-widget-no-filter", state, tab = Section.TODO)
        assertEquals(View.GONE, off.findViewById<View>(R.id.filter_bar).visibility)
        assertEquals(3, rowTexts(off).size)
    }

    private fun open(): Activity {
        val activity = instrumentation.startActivitySync(Intent(context, FilterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        return activity
    }

    private fun close(activity: Activity) {
        instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
    }

    /** The sheet saves off the main thread once it closes; wait for it. */
    private fun awaitSaved(expected: BoardFilter) {
        val deadline = System.currentTimeMillis() + 5_000
        while (FilterStore(context).load() != expected && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(expected, FilterStore(context).load())
    }

    private fun chips(root: View, group: Int): List<TextView> {
        val g = root.findViewById<ViewGroup>(group)
        return (0 until g.childCount).map { g.getChildAt(it) as TextView }
    }

    private fun picked(root: View): List<String> =
        listOf(R.id.filter_repos, R.id.filter_types, R.id.filter_priorities).flatMap { chips(root, it) }.filter { it.isSelected }.map { it.text.toString() }

    private fun text(root: View, id: Int): String = root.findViewById<TextView>(id).text.toString()

    private fun rowTexts(widget: View): List<String> = texts(widget, R.id.item_text)

    private fun noteTexts(widget: View): List<String> = texts(widget, R.id.note_text)

    /** The texts of the list's rows carrying a [id] view (the host swaps the list for a plain column). */
    private fun texts(widget: View, id: Int): List<String> {
        val list = widget.findViewById<ViewGroup>(R.id.board_list)
        return (0 until list.childCount).mapNotNull { list.getChildAt(it).findViewById<TextView>(id)?.text?.toString() }
    }

    private companion object {
        val WIDGET = Repo("VeganMarshmallow", "taskboard-widget")
        val SITE = Repo("VeganMarshmallow", "site")
        val BUGS_IN_WIDGET = BoardFilter(repos = setOf(WIDGET.fullName), types = setOf("bug"))

        fun task(n: Int, title: String, repo: Repo?, vararg labels: String, open: Boolean = true) = Issue(
            n, title, labels.map(Labels::normalize).toSet(), open,
            closedAt = if (open) null else FakeBoards.NOW.minusSeconds(7_200), repo = repo,
        )

        /** Six open tasks over two repos (and one without a repo), every type and priority, and one done. */
        fun board() = BoardBuilder().build(
            listOf(
                task(40, "Fix the crash when the list is empty", WIDGET, Labels.TODO, Labels.type("bug"), Labels.PRIORITY_HIGH),
                task(41, "Café menu page", SITE, Labels.TODO, Labels.type("feature")),
                task(42, "Filter tasks by repo and type", WIDGET, Labels.IN_PROGRESS, Labels.type("feature")),
                task(43, "Approve button misaligned on tablets", WIDGET, Labels.IN_REVIEW, Labels.type("bug")),
                task(44, "Which charting library?", null, Labels.BLOCKED, Labels.NEEDS_OWNER, Labels.type("research")),
                task(45, "Split the renderer", WIDGET, Labels.TODO, Labels.type("refactor"), Labels.PRIORITY_LOW),
                task(46, "Old crash on rotate", WIDGET, Labels.DONE, Labels.type("bug"), open = false),
            ),
            FakeBoards.NOW,
        )
    }
}
