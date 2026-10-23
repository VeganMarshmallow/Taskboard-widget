package io.github.veganmarshmallow.taskboard

import android.view.View
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.board.BoardItem
import io.github.veganmarshmallow.taskboard.core.board.BoardRow
import io.github.veganmarshmallow.taskboard.core.board.BoardRows
import io.github.veganmarshmallow.taskboard.core.board.NeedsYouReason
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.model.Priority
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.widget.RowViews
import io.github.veganmarshmallow.taskboard.widget.WidgetViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The real RemoteViews applied on a device: what each state and row actually shows. */
@RunWith(AndroidJUnit4::class)
class WidgetViewsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun applyWidget(state: WidgetState): View = onMain {
        WidgetViews(context).build(state, 1).apply(context, FrameLayout(context))
    }

    private fun applyRow(row: BoardRow): View = onMain { RowViews(context).build(row).apply(context, ListView(context)) }

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun View.visibilityOf(id: Int) = findViewById<View>(id).visibility

    @Test fun everyErrorShowsASingleBannerWithTheRightAction() {
        val errors = listOf(
            BoardError.NoToken, BoardError.BadToken(401), BoardError.BadToken(403), BoardError.NoAccess,
            BoardError.RateLimited(null), BoardError.RateLimited(FakeBoards.NOW), BoardError.Network,
            BoardError.Server(502), BoardError.Unexpected("x"), BoardError.IssueGone(3),
        )
        errors.forEach { error ->
            val v = applyWidget(WidgetState(null, error, FakeBoards.NOW))
            assertEquals("$error", View.VISIBLE, v.visibilityOf(R.id.error))
            assertTrue("$error", v.text(R.id.error).contains("tap to"))
            assertEquals(View.GONE, v.visibilityOf(R.id.board_list))
            assertEquals(context.getString(R.string.no_data), v.text(R.id.board_empty))
            assertEquals(context.getString(R.string.never_updated), v.text(R.id.updated))
        }
    }

    @Test fun initialStateShowsLoading() {
        val v = applyWidget(WidgetState.INITIAL)
        assertEquals(View.GONE, v.visibilityOf(R.id.error))
        assertEquals(context.getString(R.string.loading), v.text(R.id.board_empty))
    }

    @Test fun boardStateShowsListAndLastUpdated() {
        val v = applyWidget(WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW))
        assertEquals(View.VISIBLE, v.visibilityOf(R.id.board_list))
        assertEquals(View.GONE, v.visibilityOf(R.id.board_empty))
        assertTrue(v.text(R.id.updated).startsWith("last updated "))
    }

    @Test fun updatingPartialUpdateOnlyChangesTheHeaderText() {
        val v = applyWidget(WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW))
        onMain { WidgetViews(context).updating().reapply(context, v) }
        assertEquals(context.getString(R.string.updating), v.text(R.id.updated))
        assertEquals(View.VISIBLE, v.visibilityOf(R.id.board_list))
    }

    @Test fun everyRowOfEveryTabOfTheRealisticBoardApplies() {
        val board = FakeBoards.realistic()
        Section.entries.forEach { tab ->
            val rows = BoardRows.of(board, tab)
            rows.forEach { applyRow(it) }
            assertTrue("$tab", rows.isNotEmpty())
        }
    }

    @Test fun itemBadgesAndApproveButton() {
        fun item(priority: Priority, reason: NeedsYouReason?, approve: Boolean) =
            applyRow(BoardRow.Item(BoardItem(7, "Title", priority, approve, reason)))

        item(Priority.HIGH, NeedsYouReason.QUESTION, false).let {
            assertEquals("!?", it.text(R.id.item_badge))
            assertEquals(View.GONE, it.visibilityOf(R.id.item_approve))
        }
        item(Priority.NORMAL, NeedsYouReason.REVIEW, false).let { assertEquals("PR", it.text(R.id.item_badge)) }
        item(Priority.LOW, null, true).let {
            assertEquals(View.GONE, it.visibilityOf(R.id.item_badge))
            assertEquals(View.VISIBLE, it.visibilityOf(R.id.item_approve))
            assertEquals("#7 Title", it.text(R.id.item_text))
        }
    }

    @Test fun moreAndEmptyRows() {
        assertTrue(applyRow(BoardRow.More(Section.TODO, 1)).let { (it as TextView).text.startsWith("+1 more") })
        assertTrue(applyRow(BoardRow.More(Section.TODO, 950)).let { (it as TextView).text.startsWith("+950 more") })
        assertTrue(applyRow(BoardRow.More(Section.DONE, 2)).let { (it as TextView).text.startsWith("+2 more done") })
        val empties = Section.entries.map { (applyRow(BoardRow.Empty(it)) as TextView).text.toString() }
        assertEquals("Nothing needs you right now", empties[0])
        assertEquals("Nothing finished in the last 7 days", empties.last())
        assertEquals(Section.entries.size, empties.toSet().size) // every tab has its own message
    }

    @Test fun hebrewAndEmojiTitlesStaySingleLine() {
        val v = applyRow(BoardRow.Item(BoardItem(15, FakeBoards.HEBREW_TITLE + " 👩‍💻", Priority.NORMAL, true)))
        val text = v.findViewById<TextView>(R.id.item_text)
        assertEquals(1, text.maxLines)
        assertTrue(text.text.toString().startsWith("#15 "))
    }
}
