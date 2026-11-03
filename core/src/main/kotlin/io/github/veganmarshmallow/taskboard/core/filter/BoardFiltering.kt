package io.github.veganmarshmallow.taskboard.core.filter

import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.board.Section

/**
 * [this] board with only the tasks [filter] matches (#59), in every tab, Needs you and Done
 * included, so tab counts are of the matching tasks. An inactive filter returns the board itself.
 *
 * A tab past its cap only holds its first items, so the hidden ones can't be checked: a filtered
 * tab counts and lists the matching shown items, without a "+N more" row.
 */
fun Board.filtered(filter: BoardFilter): Board {
    if (!filter.isActive) return this
    return copy(
        sections = sections.map { content ->
            val items = content.items.filter(filter::matches)
            content.copy(items = items, totalCount = items.size)
        },
    )
}

/** Open tasks on the board (every tab but Done; a task is in one tab only). */
val Board.openCount: Int get() = sections.filter { it.section != Section.DONE }.sumOf { it.totalCount }
