package io.github.veganmarshmallow.taskboard

import android.os.Build
import android.os.Parcel
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.BoardItem
import io.github.veganmarshmallow.taskboard.core.board.BoardRows
import io.github.veganmarshmallow.taskboard.core.board.NeedsYouReason
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.board.SectionContent
import io.github.veganmarshmallow.taskboard.core.board.TitleCleaner
import io.github.veganmarshmallow.taskboard.core.model.Priority
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.widget.RowViews
import io.github.veganmarshmallow.taskboard.widget.WidgetViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task #40 review: on Android 12+ every tab's rows travel inside the widget update
 * (RemoteCollectionItems), so one update must stay well under the ~1 MB binder transaction limit
 * even for the worst board the app can build: [BoardBuilder.DEFAULT_MAX_PER_SECTION] rows per tab,
 * each with a title at [TitleCleaner.MAX_LENGTH] Hebrew characters, a badge and both Approve and
 * Merge buttons, plus the "+N more" row. Also checks what the launcher relies on for the inline
 * adapter: stable ids, the view type count and the empty row.
 */
@RunWith(AndroidJUnit4::class)
class InlineListTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before fun onlyWhereRowsAreInline() = assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

    @Test fun theWorstCaseUpdateStaysWellUnderTheBinderLimit() {
        val board = worstCase()
        Section.entries.forEach { tab ->
            val size = parcelSize(WidgetViews(context).build(WidgetState(board, null, board.fetchedAt), 1, tab))
            assertTrue("$tab: one widget update is $size bytes (budget $BUDGET_BYTES)", size < BUDGET_BYTES)
        }
    }

    @Test fun theInlineAdapterHasEveryRowWithStableIdsAndBothViewTypes() {
        val board = worstCase()
        val expected = BoardRows.of(board, Section.NEEDS_YOU)
        val list = applyList(board, Section.NEEDS_YOU)
        onMain {
            val adapter = list.adapter
            assertEquals(BoardBuilder.DEFAULT_MAX_PER_SECTION + 1, adapter.count) // items + "+N more"
            assertEquals(expected.map { it.id }, (0 until adapter.count).map(adapter::getItemId))
            assertTrue(adapter.hasStableIds())
            assertEquals(RowViews.VIEW_TYPE_COUNT, adapter.viewTypeCount)
            val types = (0 until adapter.count).map(adapter::getItemViewType).toSet()
            assertEquals("an item row and the more row use different layouts", 2, types.size)
        }
    }

    @Test fun anEmptyTabShowsItsSingleEmptyRow() {
        val board = worstCase().let { b -> b.copy(sections = b.sections.map { if (it.section == Section.TODO) SectionContent(Section.TODO, emptyList(), 0) else it }) }
        val list = applyList(board, Section.TODO)
        onMain {
            assertEquals(1, list.adapter.count)
            assertEquals(BoardRows.of(board, Section.TODO).single().id, list.adapter.getItemId(0))
        }
    }

    private fun applyList(board: Board, tab: Section): ListView = onMain {
        val root = WidgetViews(context).build(WidgetState(board, null, board.fetchedAt), 1, tab).apply(context, FrameLayout(context))
        root.findViewById<ListView>(R.id.board_list)
    }

    private fun parcelSize(views: RemoteViews): Int {
        val parcel = Parcel.obtain()
        return try {
            views.writeToParcel(parcel, 0)
            parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }

    /** Every tab full: the cap's worth of long Hebrew titles that can be both approved and merged, and more hidden. */
    private fun worstCase(): Board {
        val longTitle = TitleCleaner.clean("משימה ארוכה מאוד עם כותרת בעברית ".repeat(20))
        val sections = Section.entries.map { section ->
            val items = (1..BoardBuilder.DEFAULT_MAX_PER_SECTION).map { i ->
                BoardItem(
                    number = section.ordinal * 1000 + i + 1,
                    title = longTitle,
                    priority = Priority.HIGH,
                    canApprove = true,
                    needsYouReason = NeedsYouReason.QUESTION,
                    canMerge = true,
                )
            }
            SectionContent(section, items, items.size + 9_999)
        }
        return Board(sections, FakeBoards.NOW)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private companion object {
        /** Half the binder transaction limit, so the update has room beside other calls in flight. */
        const val BUDGET_BYTES = 512 * 1024
    }
}
