package io.github.veganmarshmallow.taskboard.core.merge

import io.github.veganmarshmallow.taskboard.core.model.Repo

/** How strongly a PR body says it belongs to a task. */
enum class TaskMatch { NONE, MENTION, EXACT }

/**
 * Matches a PR body against task #n on the board. [TaskMatch.EXACT] is the line Claude writes,
 * `Closes <owner>/<board>#<n>` (keyword case-insensitive; also `closed`/`close`), at the start of a
 * line (after optional list or emphasis marks), so "this doesn't close ...#n" is not a claim.
 * [TaskMatch.MENTION] is any other reference to the same issue (`Fixes ...#n`, `Part of ...#n`,
 * the issue URL). A longer number never matches a shorter one: #30 is not #3. Only an EXACT PR
 * is ever merged from the widget ([PrSelector]).
 */
object ClosesReference {
    private const val MAX_INPUT = 64 * 1024

    fun match(body: String, board: Repo, issue: Int): TaskMatch {
        val text = body.take(MAX_INPUT)
        val target = Regex.escape(board.fullName)
        val exact = Regex("""(?im)^[ \t>*_-]*close[sd]?[*_]*:?[*_]*[ \t]+$target#$issue(?!\d)""")
        val mention = Regex("""(?i)(?:\b$target#|github\.com/$target/issues/)$issue(?!\d)""")
        return when {
            exact.containsMatchIn(text) -> TaskMatch.EXACT
            mention.containsMatchIn(text) -> TaskMatch.MENTION
            else -> TaskMatch.NONE
        }
    }
}
