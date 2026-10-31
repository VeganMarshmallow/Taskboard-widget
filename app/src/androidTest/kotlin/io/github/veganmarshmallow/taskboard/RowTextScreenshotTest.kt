package io.github.veganmarshmallow.taskboard

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.TabTestViews.configured
import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** #12: list rows show only their titles, without issue numbers (and the finished look with #10's tabs). */
@Screenshot
@RunWith(AndroidJUnit4::class)
class RowTextScreenshotTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val board = WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW)

    private fun View.rowTexts(): List<String> {
        val list = findViewById<ViewGroup>(R.id.board_list)
        return (0 until list.childCount).mapNotNull { list.getChildAt(it).findViewById<TextView>(R.id.item_text)?.text?.toString() }
    }

    @Test fun rowsWithoutNumbersNeedsYou() {
        val texts = ScreenshotCapture.widget("12-1-rows-needs-you", board).rowTexts()
        assertEquals(listOf("Add dark mode to the portfolio site"), texts) // the blocked question is in Blocked (#34)
    }

    @Test fun rowsWithoutNumbersTodo() {
        val texts = ScreenshotCapture.widget("12-2-rows-todo", board, tab = Section.TODO).rowTexts()
        assertTrue(texts.isNotEmpty())
        texts.forEach { assertTrue(it, !it.startsWith("#")) }
    }

    @Test fun rowsWithoutNumbersInProgressHebrew() {
        val texts = ScreenshotCapture.widget("12-3-rows-in-progress-hebrew", board, tab = Section.IN_PROGRESS).rowTexts()
        assertEquals(listOf("Android board widget", FakeBoards.HEBREW_TITLE), texts)
    }

    @Test fun finalLookTabsAndRowsWithoutNumbers() {
        // The finished look after #10 and #12: tabs with counts, rows with only their titles,
        // including titles that start with their own "#", a Hebrew one and an empty one.
        fun issue(n: Int, title: String, vararg labels: String) = Issue(n, title, labels.map(Labels::normalize).toSet(), true, null)
        val l = Labels
        val board = BoardBuilder().build(
            listOf(
                issue(21, "#3 follow-up: retry on timeout", l.TODO),
                issue(22, "תיקון באג בכפתור השמירה", l.TODO, l.READY_FOR_CLAUDE),
                issue(23, "   ", l.TODO, l.READY_FOR_CLAUDE),
                issue(24, "Weekly digest email ✉️", l.TODO, l.READY_FOR_CLAUDE, l.PRIORITY_HIGH),
                issue(25, "Which charting library?", l.BLOCKED, l.NEEDS_OWNER),
                issue(26, "Android board widget", l.IN_PROGRESS),
            ),
            FakeBoards.NOW,
        )
        val state = WidgetState(board, null, FakeBoards.NOW)
        val texts = ScreenshotCapture.widget("12-4-final-look-tabs-and-rows", state, heightDp = 330, tab = Section.TODO).rowTexts()
        assertEquals(listOf("Weekly digest email ✉️", "#3 follow-up: retry on timeout", "תיקון באג בכפתור השמירה", "(untitled)"), texts)
        val dark = ScreenshotCapture.widget("12-5-final-look-dark", state, heightDp = 330, tab = Section.TODO, host = configured(context, night = true)).rowTexts()
        assertEquals(texts, dark)
    }
}
