package io.github.veganmarshmallow.taskboard.core.board

import io.github.veganmarshmallow.taskboard.core.model.Priority

/**
 * The short marker before a row's title: `!` for high priority, then `?` when the owner has a
 * question to answer or `PR` for a review. A question in Needs you reads `?` (`!?` with
 * `priority: high`). A blocked task waiting on the owner always reads `!?` in the Blocked tab,
 * whatever its priority (owner's decision, #39), so it stands out among tasks blocked on other
 * things. Empty when there is nothing to mark.
 */
object RowBadge {
    fun of(item: BoardItem, section: Section): String = buildString {
        val blockedQuestion = section == Section.BLOCKED && item.needsYouReason == NeedsYouReason.QUESTION
        if (item.priority == Priority.HIGH || blockedQuestion) append("!")
        when (item.needsYouReason) {
            NeedsYouReason.QUESTION -> append("?")
            NeedsYouReason.REVIEW -> append("PR")
            null -> Unit
        }
    }
}
