package io.github.veganmarshmallow.taskboard.core.board

/** The text on a tab: its short name and count, e.g. "Todo 3". Huge counts are capped at "999+". */
object TabLabel {
    const val MAX_SHOWN = 999

    fun of(section: Section, count: Int): String = "${section.shortLabel} ${countText(count)}"

    fun of(board: Board, section: Section): String = of(section, board.count(section))

    fun countText(count: Int): String = when {
        count < 0 -> "0"
        count > MAX_SHOWN -> "$MAX_SHOWN+"
        else -> count.toString()
    }
}
