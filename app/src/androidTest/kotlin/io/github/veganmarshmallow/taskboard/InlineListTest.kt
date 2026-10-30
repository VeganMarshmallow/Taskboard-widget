package io.github.veganmarshmallow.taskboard

import android.os.Build
import android.os.Parcel
import android.os.SystemClock
import android.widget.ListAdapter
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
import io.github.veganmarshmallow.taskboard.data.TabStore
import io.github.veganmarshmallow.taskboard.widget.RowViews
import io.github.veganmarshmallow.taskboard.widget.WidgetViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    /**
     * Through a real widget host, so the worst-case update also really crosses the binder to the
     * system and back. (A plain RemoteViews.apply leaves the list's adapter unset on API 35.)
     */
    @Test fun theInlineAdapterHasEveryRowWithStableIdsAndBothViewTypes() {
        val board = worstCase()
        val expected = BoardRows.of(board, Section.NEEDS_YOU).map { it.id }
        withHost(board) { host ->
            val list = awaitList(host, "the worst-case Needs you rows") { it.count == expected.size }
            onMain {
                val adapter = list.adapter
                assertEquals(BoardBuilder.DEFAULT_MAX_PER_SECTION + 1, adapter.count) // items + "+N more"
                assertEquals(expected, (0 until adapter.count).map(adapter::getItemId))
                assertTrue(adapter.hasStableIds())
                assertEquals(RowViews.VIEW_TYPE_COUNT, adapter.viewTypeCount)
                val types = (0 until adapter.count).map(adapter::getItemViewType).toSet()
                assertEquals("an item row and the more row use different layouts", 2, types.size)
            }
        }
    }

    @Test fun anEmptyTabShowsItsSingleEmptyRow() {
        val board = worstCase().let { b -> b.copy(sections = b.sections.map { if (it.section == Section.TODO) SectionContent(Section.TODO, emptyList(), 0) else it }) }
        val emptyId = BoardRows.of(board, Section.TODO).single().id
        withHost(board) { host ->
            host.tap(Section.TODO)
            host.awaitHighlighted(Section.TODO, "tap on the empty Todo tab")
            awaitList(host, "the empty Todo row") { it.count == 1 && it.getItemId(0) == emptyId }
        }
    }

    private fun withHost(board: Board, block: (RealWidgetHost) -> Unit) {
        val host = RealWidgetHost(instrumentation)
        try {
            host.seed(WidgetState(board, null, board.fetchedAt))
            block(host)
        } finally {
            TabStore(context).clear(intArrayOf(host.widgetId))
            host.close()
        }
    }

    /** Waits until the hosted list's adapter matches [ok]; the host applies updates asynchronously. */
    private fun awaitList(host: RealWidgetHost, what: String, ok: (ListAdapter) -> Boolean): ListView {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        var last = "no adapter"
        while (SystemClock.uptimeMillis() < deadline) {
            val list = onMain { host.view.findViewById<ListView>(R.id.board_list) }
            val matched = onMain { list?.adapter?.let { a -> last = "${a.count} rows"; ok(a) } ?: false }
            if (matched) return list
            SystemClock.sleep(POLL_MS)
        }
        fail("timed out waiting for $what (last: $last)")
        throw AssertionError()
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
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 50L
    }
}
