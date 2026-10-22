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
    @Test fun emptyBoardShowsEmptyRowAndDoneCount() {
        val board = BoardBuilder().build(listOf(issue(5, Labels.DONE, open = false, closedAt = NOW)), NOW)
        assertEquals(listOf(BoardRow.Empty, BoardRow.Done(1)), BoardRows.of(board))
    }

    @Test fun skipsEmptySectionsAndKeepsOrder() {
        val board = BoardBuilder().build(listOf(issue(3, Labels.TODO), issue(4, Labels.IN_REVIEW)), NOW)
        val rows = BoardRows.of(board)
        assertEquals(
            listOf("H:NEEDS_YOU:1", "I:4", "H:TODO:1", "I:3", "D:0"),
            rows.map {
                when (it) {
                    is BoardRow.Header -> "H:${it.section}:${it.count}"
                    is BoardRow.Item -> "I:${it.item.number}"
                    is BoardRow.More -> "M:${it.section}:${it.hidden}"
                    is BoardRow.Done -> "D:${it.count}"
                    BoardRow.Empty -> "E"
                }
            },
        )
    }

    @Test fun cappedSectionGetsMoreRow() {
        val board = BoardBuilder(maxPerSection = 2).build((2..6).map { issue(it, Labels.BLOCKED) }, NOW)
        val rows = BoardRows.of(board)
        assertEquals(BoardRow.Header(Section.BLOCKED, 5), rows[0])
        assertEquals(BoardRow.More(Section.BLOCKED, 3), rows[3])
    }

    @Test fun rowIdsAreUnique() {
        val issues = (2..60).map { issue(it, listOf(Labels.TODO, Labels.BLOCKED, Labels.IN_PROGRESS, Labels.IN_REVIEW)[it % 4]) }
        val rows = BoardRows.of(BoardBuilder(maxPerSection = 5).build(issues, NOW))
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
        assertTrue(rows.any { it is BoardRow.More })
    }
}
