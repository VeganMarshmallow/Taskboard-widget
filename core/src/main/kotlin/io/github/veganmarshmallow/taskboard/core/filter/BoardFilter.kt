package io.github.veganmarshmallow.taskboard.core.filter

import io.github.veganmarshmallow.taskboard.core.board.BoardItem
import io.github.veganmarshmallow.taskboard.core.model.Priority

/**
 * Which tasks the board shows (#59). Within one dimension any picked value matches (repo A *or*
 * repo B); across dimensions all must match (a bug *and* in repo A). An empty dimension doesn't
 * restrict. [text] matches the title, `#number` or repo ([TextQuery]). [NONE] shows everything.
 *
 * Repos and types compare ignoring case (GitHub names and labels are case-insensitive); the
 * spelling picked is kept for display.
 */
data class BoardFilter(
    val repos: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val priorities: Set<Priority> = emptySet(),
    val text: String = "",
) {
    private val repoKeys: Set<String> = repos.mapTo(HashSet()) { key(it) }
    private val typeKeys: Set<String> = types.mapTo(HashSet()) { key(it) }
    private val query = TextQuery(text)

    /** True when anything is picked or typed: the board then hides what doesn't match. */
    val isActive: Boolean get() = activeCount > 0

    /** How many values are picked, plus one for search text: the number on the filter badge. */
    val activeCount: Int get() = repos.size + types.size + priorities.size + (if (query.isEmpty) 0 else 1)

    fun matches(item: BoardItem): Boolean =
        (repoKeys.isEmpty() || item.repo?.let { key(it) in repoKeys } == true) &&
            (typeKeys.isEmpty() || item.types.any { key(it) in typeKeys }) &&
            (priorities.isEmpty() || item.priority in priorities) &&
            query.matches(item)

    fun hasRepo(repo: String): Boolean = key(repo) in repoKeys
    fun hasType(type: String): Boolean = key(type) in typeKeys

    fun toggleRepo(repo: String): BoardFilter = copy(repos = repos.toggled(repo))
    fun toggleType(type: String): BoardFilter = copy(types = types.toggled(type))
    fun togglePriority(priority: Priority): BoardFilter =
        copy(priorities = if (priority in priorities) priorities - priority else priorities + priority)

    fun withText(newText: String): BoardFilter = copy(text = newText)

    companion object {
        val NONE = BoardFilter()

        internal fun key(value: String): String = value.trim().lowercase()

        /** Removes [value] if present (ignoring case), otherwise adds it; blank values are ignored. */
        private fun Set<String>.toggled(value: String): Set<String> {
            if (value.isBlank()) return this
            val k = key(value)
            return if (any { key(it) == k }) filterTo(LinkedHashSet()) { key(it) != k } else this + value.trim()
        }
    }
}
