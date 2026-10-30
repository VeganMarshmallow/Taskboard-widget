package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.BoardItem
import io.github.veganmarshmallow.taskboard.core.board.NeedsYouReason
import io.github.veganmarshmallow.taskboard.core.board.RowBadge
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.model.Priority
import org.junit.Assert.assertEquals
import org.junit.Test

/** The row marker (#34 review): `?`/`!?` mean the same in Needs you and in Blocked. */
class RowBadgeTest {
    private fun item(priority: Priority, reason: NeedsYouReason?) = BoardItem(2, "T", priority, false, reason)

    @Test fun everyPriorityAndReason() {
        assertEquals("", RowBadge.of(item(Priority.NORMAL, null)))
        assertEquals("", RowBadge.of(item(Priority.LOW, null)))
        assertEquals("!", RowBadge.of(item(Priority.HIGH, null)))
        assertEquals("?", RowBadge.of(item(Priority.NORMAL, NeedsYouReason.QUESTION)))
        assertEquals("?", RowBadge.of(item(Priority.LOW, NeedsYouReason.QUESTION)))
        assertEquals("!?", RowBadge.of(item(Priority.HIGH, NeedsYouReason.QUESTION)))
        assertEquals("PR", RowBadge.of(item(Priority.NORMAL, NeedsYouReason.REVIEW)))
        assertEquals("!PR", RowBadge.of(item(Priority.HIGH, NeedsYouReason.REVIEW)))
    }

    @Test fun aBlockedQuestionHasTheSameBadgeAsAQuestionInNeedsYou() {
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
            assertEquals("$priority", RowBadge.of(needsYou), RowBadge.of(blocked))
            assertEquals("$priority", if (priority == listOf(Labels.PRIORITY_HIGH)) "!?" else "?", RowBadge.of(blocked))
        }
    }

    @Test fun aPlainBlockedTaskHasNoQuestionMark() {
        val board = BoardBuilder().build(listOf(issue(2, Labels.BLOCKED), issue(3, Labels.BLOCKED, Labels.PRIORITY_HIGH)), NOW)
        assertEquals(listOf("", "!"), board.section(Section.BLOCKED).items.sortedBy { it.number }.map(RowBadge::of))
    }
}
