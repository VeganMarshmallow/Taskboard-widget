package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.CountBadge
import io.github.veganmarshmallow.taskboard.core.board.CountBadge.Capped
import io.github.veganmarshmallow.taskboard.core.board.CountBadge.Exact
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.model.Labels
import org.junit.Assert.assertEquals
import org.junit.Test

class CountBadgeTest {
    @Test fun countsUpToTheCapAreExact() {
        assertEquals(Exact(0), CountBadge.of(0))
        assertEquals(Exact(1), CountBadge.of(1))
        assertEquals(Exact(998), CountBadge.of(998))
        assertEquals(Exact(999), CountBadge.of(999))
        assertEquals(999, CountBadge.MAX_SHOWN)
    }

    @Test fun countsPastTheCapAreCapped() {
        assertEquals(Capped(999), CountBadge.of(1000))
        assertEquals(Capped(999), CountBadge.of(1_000_000))
        assertEquals(Capped(999), CountBadge.of(Int.MAX_VALUE))
    }

    @Test fun negativeCountsShowAsZero() {
        assertEquals(Exact(0), CountBadge.of(-1))
        assertEquals(Exact(0), CountBadge.of(-5))
        assertEquals(Exact(0), CountBadge.of(Int.MIN_VALUE))
    }

    @Test fun boardCountsAreTrueTotalsNotTheCappedItemCount() {
        val board = BoardBuilder(maxPerSection = 3).build((2..1500).map { issue(it, Labels.TODO) }, NOW)
        assertEquals(3, board.section(Section.TODO).items.size)
        assertEquals(Capped(999), CountBadge.of(board, Section.TODO))
        assertEquals(Exact(0), CountBadge.of(board, Section.NEEDS_YOU))
    }
}
