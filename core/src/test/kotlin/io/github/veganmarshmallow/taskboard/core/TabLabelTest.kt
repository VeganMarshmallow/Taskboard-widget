package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.board.TabLabel
import io.github.veganmarshmallow.taskboard.core.model.Labels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TabLabelTest {
    @Test fun labelsAreShortNameAndCount() {
        assertEquals("Needs you 2", TabLabel.of(Section.NEEDS_YOU, 2))
        assertEquals("Todo 3", TabLabel.of(Section.TODO, 3))
        assertEquals("Doing 0", TabLabel.of(Section.IN_PROGRESS, 0))
        assertEquals("Blocked 1", TabLabel.of(Section.BLOCKED, 1))
        assertEquals("Review 4", TabLabel.of(Section.IN_REVIEW, 4))
        assertEquals("Done 7", TabLabel.of(Section.DONE, 7))
    }

    @Test fun shortLabelsStayShortEnoughForAThirdOfANarrowWidget() {
        // Three tabs per row at the 180dp minimum leave ~44dp of text each: about 11 characters at 8sp.
        Section.entries.forEach { assertTrue(it.name, TabLabel.of(it, 9).length <= 11) }
        Section.entries.forEach { assertTrue(it.name, it.shortLabel.length <= it.title.length) }
    }

    @Test fun hugeAndNegativeCountsAreClamped() {
        assertEquals("Todo 999", TabLabel.of(Section.TODO, 999))
        assertEquals("Todo 999+", TabLabel.of(Section.TODO, 1000))
        assertEquals("Todo 999+", TabLabel.of(Section.TODO, Int.MAX_VALUE))
        assertEquals("Todo 0", TabLabel.of(Section.TODO, -5))
        assertEquals("0", TabLabel.countText(Int.MIN_VALUE))
    }

    @Test fun boardCountsAreTrueTotalsNotTheCappedItemCount() {
        val board = BoardBuilder(maxPerSection = 3).build((2..1500).map { issue(it, Labels.TODO) }, NOW)
        assertEquals(3, board.section(Section.TODO).items.size)
        assertEquals("Todo 999+", TabLabel.of(board, Section.TODO))
        assertEquals("Needs you 0", TabLabel.of(board, Section.NEEDS_YOU))
    }
}
