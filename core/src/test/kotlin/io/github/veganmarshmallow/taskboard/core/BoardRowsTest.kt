package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.BoardRow
import io.github.veganmarshmallow.taskboard.core.board.BoardRows
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.model.Labels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardRowsTest {
    private fun BoardRow.code() = when (this) {
        is BoardRow.Item -> "I:${item.number}"
        is BoardRow.More -> "M:$section:$hidden"
        is BoardRow.Empty -> "E:$section"
    }

    @Test fun emptyTabShowsOnlyItsEmptyRow() {
        val board = BoardBuilder().build(listOf(issue(5, Labels.DONE, open = false, closedAt = NOW)), NOW)
        Section.entries.filter { it != Section.DONE }.forEach {
            assertEquals(listOf(BoardRow.Empty(it)), BoardRows.of(board, it))
        }
        assertEquals(listOf("I:5"), BoardRows.of(board, Section.DONE).map { it.code() })
    }

    @Test fun eachTabListsOnlyItsItems() {
        val board = BoardBuilder().build(listOf(issue(3, Labels.TODO), issue(4, Labels.IN_REVIEW)), NOW)
        assertEquals(listOf("I:3"), BoardRows.of(board, Section.TODO).map { it.code() })
        assertEquals(listOf("I:4"), BoardRows.of(board, Section.NEEDS_YOU).map { it.code() })
        assertEquals(listOf("I:4"), BoardRows.of(board, Section.IN_REVIEW).map { it.code() })
        assertEquals(listOf("E:BLOCKED"), BoardRows.of(board, Section.BLOCKED).map { it.code() })
    }

    @Test fun cappedTabGetsMoreRow() {
        val board = BoardBuilder(maxPerSection = 2).build((2..6).map { issue(it, Labels.BLOCKED) }, NOW)
        val rows = BoardRows.of(board, Section.BLOCKED)
        assertEquals(listOf("I:2", "I:3", "M:BLOCKED:3"), rows.map { it.code() })
    }

    @Test fun rowIdsAreUniqueWithinEveryTab() {
        val issues = (2..60).map { issue(it, listOf(Labels.TODO, Labels.BLOCKED, Labels.IN_PROGRESS, Labels.IN_REVIEW)[it % 4]) }
        val board = BoardBuilder(maxPerSection = 5).build(issues, NOW)
        Section.entries.forEach { tab ->
            val rows = BoardRows.of(board, tab)
            assertEquals("$tab", rows.size, rows.map { it.id }.toSet().size)
        }
        assertTrue(BoardRows.of(board, Section.TODO).any { it is BoardRow.More })
    }
}
