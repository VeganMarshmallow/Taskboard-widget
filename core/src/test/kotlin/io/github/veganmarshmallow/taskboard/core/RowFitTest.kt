package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.BoardItem
import io.github.veganmarshmallow.taskboard.core.board.MergeLane
import io.github.veganmarshmallow.taskboard.core.board.MergeWait
import io.github.veganmarshmallow.taskboard.core.board.RowFit
import io.github.veganmarshmallow.taskboard.core.board.RowFit.Extras
import io.github.veganmarshmallow.taskboard.core.model.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #49: which rows, widths and font scales get the narrow two-line row. */
class RowFitTest {
    private fun item(approve: Boolean = false, merge: Boolean = false, lane: MergeLane? = null) =
        BoardItem(2, "T", Priority.NORMAL, approve, canMerge = merge, mergeLane = lane)

    @Test fun extrasFollowTheRowContent() {
        assertEquals(Extras.NONE, RowFit.extrasOf(item()))
        assertEquals(Extras.BUTTON, RowFit.extrasOf(item(approve = true)))
        assertEquals(Extras.BUTTON, RowFit.extrasOf(item(merge = true)))
        assertEquals(Extras.BUTTON, RowFit.extrasOf(item(merge = true, lane = MergeLane.NEXT)))
        assertEquals(Extras.BUTTON, RowFit.extrasOf(item(merge = true, lane = MergeLane(false, null, null))))
        assertEquals(Extras.BUTTON_AND_REASON, RowFit.extrasOf(item(merge = true, lane = MergeLane(false, MergeWait.CONFLICT_CLAUDE_FIXES, "A"))))
        assertEquals(Extras.BUTTON_AND_REASON, RowFit.extrasOf(item(approve = true, merge = true)))
        // A lane without canMerge shows no button, so no reason either.
        assertEquals(Extras.NONE, RowFit.extrasOf(item(lane = MergeLane(false, MergeWait.QUEUED, null))))
    }

    @Test fun rowsWithoutButtonsNeverGoNarrow() {
        listOf(1, 180, 250).forEach { assertFalse("${it}dp", RowFit.isNarrow(it, 2f, Extras.NONE)) }
    }

    @Test fun minWidthIsNarrowAtEveryFontScaleForButtonRows() {
        listOf(0.85f, 1f, 1.3f, 2f).forEach { scale ->
            assertTrue("button x$scale", RowFit.isNarrow(180, scale, Extras.BUTTON))
            assertTrue("reason x$scale", RowFit.isNarrow(180, scale, Extras.BUTTON_AND_REASON))
        }
    }

    @Test fun defaultWidthAtNormalFontKeepsOneLineUnlessThereIsAReason() {
        assertFalse(RowFit.isNarrow(250, 1f, Extras.BUTTON))
        assertTrue(RowFit.isNarrow(250, 1f, Extras.BUTTON_AND_REASON))
        assertFalse(RowFit.isNarrow(340, 1f, Extras.BUTTON_AND_REASON))
    }

    @Test fun boundariesAreInclusiveForWide() {
        assertFalse(RowFit.isNarrow(RowFit.BUTTON_MIN_DP, 1f, Extras.BUTTON))
        assertTrue(RowFit.isNarrow(RowFit.BUTTON_MIN_DP - 1, 1f, Extras.BUTTON))
        assertFalse(RowFit.isNarrow(RowFit.REASON_MIN_DP, 1f, Extras.BUTTON_AND_REASON))
        assertTrue(RowFit.isNarrow(RowFit.REASON_MIN_DP - 1, 1f, Extras.BUTTON_AND_REASON))
        assertFalse(RowFit.isNarrow(600, 2f, Extras.BUTTON_AND_REASON))
        assertTrue(RowFit.isNarrow(599, 2f, Extras.BUTTON_AND_REASON))
    }

    @Test fun bigFontMakesANormalWidthNarrow() {
        assertTrue(RowFit.isNarrow(340, 2f, Extras.BUTTON))
        assertTrue(RowFit.isNarrow(250, 1.3f, Extras.BUTTON))
        assertFalse(RowFit.isNarrow(340, 1.3f, Extras.BUTTON))
        assertTrue(RowFit.isNarrow(340, 1.3f, Extras.BUTTON_AND_REASON))
    }

    @Test fun smallFontLetsANarrowerWidgetKeepOneLine() {
        assertFalse(RowFit.isNarrow(260, 0.85f, Extras.BUTTON_AND_REASON))
        assertFalse(RowFit.isNarrow(190, 0.85f, Extras.BUTTON))
    }

    @Test fun unknownWidthKeepsTheOldOneLineRow() {
        Extras.values().forEach { extras ->
            assertFalse(RowFit.isNarrow(null, 2f, extras))
            assertFalse(RowFit.isNarrow(0, 2f, extras))
            assertFalse(RowFit.isNarrow(-5, 1f, extras))
        }
    }

    @Test fun nonsenseFontScaleCountsAsOne() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { scale ->
            assertTrue("200dp x$scale", RowFit.isNarrow(200, scale, Extras.BUTTON))
            assertFalse("250dp x$scale", RowFit.isNarrow(250, scale, Extras.BUTTON))
            assertTrue("250dp reason x$scale", RowFit.isNarrow(250, scale, Extras.BUTTON_AND_REASON))
            assertFalse("340dp reason x$scale", RowFit.isNarrow(340, scale, Extras.BUTTON_AND_REASON))
        }
    }

    @Test fun hugeWidthIsWide() {
        assertFalse(RowFit.isNarrow(Int.MAX_VALUE, 2f, Extras.BUTTON_AND_REASON))
    }
}
