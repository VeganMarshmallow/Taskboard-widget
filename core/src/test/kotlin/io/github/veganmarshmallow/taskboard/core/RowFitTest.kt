package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.RowFit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #49: which widths and font scales get the narrow two-line row. */
class RowFitTest {
    @Test fun minWidthIsNarrowAtEveryFontScale() {
        listOf(0.85f, 1f, 1.3f, 2f).forEach { assertTrue("180dp x$it", RowFit.isNarrow(180, it)) }
    }

    @Test fun normalWidthAtNormalFontKeepsOneLine() {
        assertFalse(RowFit.isNarrow(340, 1f))
        assertFalse(RowFit.isNarrow(RowFit.WIDE_MIN_DP, 1f))
    }

    @Test fun boundaryIsInclusiveForWide() {
        assertTrue(RowFit.isNarrow(RowFit.WIDE_MIN_DP - 1, 1f))
        assertFalse(RowFit.isNarrow(600, 2f))
        assertTrue(RowFit.isNarrow(599, 2f))
    }

    @Test fun bigFontMakesANormalWidthNarrow() {
        assertTrue(RowFit.isNarrow(340, 2f))
        assertTrue(RowFit.isNarrow(340, 1.3f))
    }

    @Test fun smallFontLetsANarrowerWidgetKeepOneLine() {
        assertFalse(RowFit.isNarrow(260, 0.85f))
    }

    @Test fun unknownWidthKeepsTheOldOneLineRow() {
        assertFalse(RowFit.isNarrow(null, 2f))
        assertFalse(RowFit.isNarrow(0, 2f))
        assertFalse(RowFit.isNarrow(-5, 1f))
    }

    @Test fun nonsenseFontScaleCountsAsOne() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { scale ->
            assertTrue("250dp x$scale", RowFit.isNarrow(250, scale))
            assertFalse("340dp x$scale", RowFit.isNarrow(340, scale))
        }
    }

    @Test fun hugeWidthIsWide() {
        assertFalse(RowFit.isNarrow(Int.MAX_VALUE, 2f))
    }
}
