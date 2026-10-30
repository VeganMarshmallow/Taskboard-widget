package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.BoardItem
import io.github.veganmarshmallow.taskboard.core.board.BoardRow
import io.github.veganmarshmallow.taskboard.core.board.BoardRows
import io.github.veganmarshmallow.taskboard.core.board.NeedsYouReason
import io.github.veganmarshmallow.taskboard.core.board.RowBadge
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.model.Priority
import org.junit.Assert.assertEquals
import org.junit.Test

/** The row marker: `?`/`!?` in Needs you by priority; a blocked question is always `!?` (#39). */
class RowBadgeTest {
    private fun item(priority: Priority, reason: NeedsYouReason?) = BoardItem(2, "T", priority, false, reason)

    @Test fun everyPriorityAndReason() {
        assertEquals("", RowBadge.of(item(Priority.NORMAL, null), Section.NEEDS_YOU))
        assertEquals("", RowBadge.of(item(Priority.LOW, null), Section.NEEDS_YOU))
        assertEquals("!", RowBadge.of(item(Priority.HIGH, null), Section.NEEDS_YOU))
        assertEquals("?", RowBadge.of(item(Priority.NORMAL, NeedsYouReason.QUESTION), Section.NEEDS_YOU))
        assertEquals("?", RowBadge.of(item(Priority.LOW, NeedsYouReason.QUESTION), Section.NEEDS_YOU))
        assertEquals("!?", RowBadge.of(item(Priority.HIGH, NeedsYouReason.QUESTION), Section.NEEDS_YOU))
        assertEquals("PR", RowBadge.of(item(Priority.NORMAL, NeedsYouReason.REVIEW), Section.NEEDS_YOU))
        assertEquals("!PR", RowBadge.of(item(Priority.HIGH, NeedsYouReason.REVIEW), Section.NEEDS_YOU))
    }

    @Test fun aBlockedQuestionAlwaysShowsBangQuestionWhateverItsPriority() {
        listOf(emptyList(), listOf(Labels.PRIORITY_HIGH), listOf(Labels.PRIORITY_LOW)).forEach { priority ->
            val board = BoardBuilder().build(
                listOf(
                    issue(2, Labels.BLOCKED, Labels.NEEDS_OWNER, *priority.toTypedArray()),
                    issue(3, Labels.TODO, Labels.NEEDS_OWNER, *priority.toTypedArray()),
                ),
                NOW,
            )
            val blocked = board.section(Section.BLOCKED).items.single()
            val needsYou = board.section(Section.NEEDS_YOU).items.single()
            assertEquals("$priority", "!?", RowBadge.of(blocked, Section.BLOCKED))
            val expectedNeedsYou = if (priority == listOf(Labels.PRIORITY_HIGH)) "!?" else "?"
            assertEquals("$priority", expectedNeedsYou, RowBadge.of(needsYou, Section.NEEDS_YOU))
        }
    }

    @Test fun rowsCarryTheirTabSoTheWidgetPicksTheRightBadge() {
        val board = BoardBuilder().build(listOf(issue(2, Labels.BLOCKED, Labels.NEEDS_OWNER)), NOW)
        val row = BoardRows.of(board, Section.BLOCKED).filterIsInstance<BoardRow.Item>().single()
        assertEquals(Section.BLOCKED, row.section)
        assertEquals("!?", RowBadge.of(row.item, row.section))
    }

    @Test fun aPlainBlockedTaskHasNoQuestionMark() {
        val board = BoardBuilder().build(listOf(issue(2, Labels.BLOCKED), issue(3, Labels.BLOCKED, Labels.PRIORITY_HIGH)), NOW)
        assertEquals(listOf("", "!"), board.section(Section.BLOCKED).items.sortedBy { it.number }.map { RowBadge.of(it, Section.BLOCKED) })
    }
}
