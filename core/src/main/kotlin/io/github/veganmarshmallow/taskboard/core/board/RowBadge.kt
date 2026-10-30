package io.github.veganmarshmallow.taskboard.core.board

import io.github.veganmarshmallow.taskboard.core.model.Priority

/**
 * The short marker before a row's title, the same in every tab: `!` for high priority, then `?`
 * when the owner has a question to answer or `PR` for a review. So a question reads `?`, or `!?`
 * with `priority: high`. A blocked task waiting on the owner sits in Blocked (#34) with the same
 * `?`/`!?` as a question in Needs you; `!` is never added just because a task is blocked.
 * Empty when there is nothing to mark.
 */
object RowBadge {
    fun of(item: BoardItem): String = buildString {
        if (item.priority == Priority.HIGH) append("!")
        when (item.needsYouReason) {
            NeedsYouReason.QUESTION -> append("?")
            NeedsYouReason.REVIEW -> append("PR")
            null -> Unit
        }
    }
}
