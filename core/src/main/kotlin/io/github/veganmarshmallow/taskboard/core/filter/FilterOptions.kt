package io.github.veganmarshmallow.taskboard.core.filter

import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.model.Priority
import io.github.veganmarshmallow.taskboard.core.task.TaskType

/**
 * The values the filter offers (#59): only those that occur on [Board], so every choice finds
 * something. Values picked earlier but no longer on the board (the repo's tasks all finished) are
 * still offered, so the owner can see and un-pick them instead of wondering why the board is empty.
 */
data class FilterOptions(
    val repos: List<String>,
    val types: List<String>,
    val priorities: List<Priority>,
) {
    val isEmpty: Boolean get() = repos.isEmpty() && types.isEmpty() && priorities.isEmpty()

    companion object {
        private val KNOWN_TYPES = TaskType.entries.map { it.value }

        fun of(board: Board?, filter: BoardFilter = BoardFilter.NONE): FilterOptions {
            val items = board?.sections.orEmpty().flatMap { it.items }
            return FilterOptions(
                repos = distinct(items.mapNotNull { it.repo } + filter.repos)
                    .sortedWith(String.CASE_INSENSITIVE_ORDER),
                types = distinct(items.flatMap { it.types } + filter.types)
                    .sortedWith(compareBy<String>({ typeRank(it) }, { BoardFilter.key(it) })),
                priorities = (items.map { it.priority } + filter.priorities).distinct().sorted(),
            )
        }

        /** The issue form's types first, in its order; any other `type:` label after them, A to Z. */
        private fun typeRank(type: String): Int = KNOWN_TYPES.indexOf(BoardFilter.key(type)).let { if (it < 0) KNOWN_TYPES.size else it }

        /** First spelling of each value, ignoring case; blanks dropped. */
        private fun distinct(values: List<String>): List<String> =
            values.filter { it.isNotBlank() }.map { it.trim() }.distinctBy { BoardFilter.key(it) }
    }
}
