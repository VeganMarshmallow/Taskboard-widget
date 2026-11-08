package io.github.veganmarshmallow.taskboard.core.questions

import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.board.NeedsYouReason

/**
 * The widget header's Questions badge (task #82): how many tasks wait on the owner's answer
 * (`needs-owner`), from the board the widget already has, so it costs no extra request. Questions
 * found only in comments show on the Questions screen. [text] is what the badge shows.
 */
object QuestionBadge {
    const val MAX_SHOWN = 9

    fun count(board: Board?): Int = board?.sections.orEmpty()
        .flatMap { it.items }
        .filter { it.needsYouReason == NeedsYouReason.QUESTION }
        .distinctBy { it.number }
        .size

    /** "?" with no questions, the count up to [MAX_SHOWN], then "9+". */
    fun text(count: Int): String = when {
        count <= 0 -> "?"
        count > MAX_SHOWN -> "$MAX_SHOWN+"
        else -> count.toString()
    }
}
