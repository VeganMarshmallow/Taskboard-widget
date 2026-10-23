package io.github.veganmarshmallow.taskboard.core.merge

import io.github.veganmarshmallow.taskboard.core.model.Repo

/** How strongly a PR body says it belongs to a task. */
enum class TaskMatch { NONE, MENTION, EXACT }

/**
 * Matches a PR body against task #n on the board. [TaskMatch.EXACT] is the line Claude writes,
 * `Closes <owner>/<board>#<n>` (keyword case-insensitive; also `closed`/`close`). [TaskMatch.MENTION]
 * is any other reference to the same issue (`Fixes ...#n`, `Part of ...#n`, the issue URL). A
 * longer number never matches a shorter one: #30 is not #3.
 */
object ClosesReference {
    private const val MAX_INPUT = 64 * 1024

    fun match(body: String, board: Repo, issue: Int): TaskMatch {
        val text = body.take(MAX_INPUT)
        val target = Regex.escape(board.fullName)
        val exact = Regex("""(?i)\bclose[sd]?:?[ \t]+$target#$issue(?!\d)""")
        val mention = Regex("""(?i)(?:\b$target#|github\.com/$target/issues/)$issue(?!\d)""")
        return when {
            exact.containsMatchIn(text) -> TaskMatch.EXACT
            mention.containsMatchIn(text) -> TaskMatch.MENTION
            else -> TaskMatch.NONE
        }
    }
}
