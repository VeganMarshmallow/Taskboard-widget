package io.github.veganmarshmallow.taskboard.core.board

/**
 * The list rows for one tab: its items and a "+N more" row if the tab is capped, or a single
 * [BoardRow.Empty] row when the tab has nothing in it. [filtered]: [board] went through an active
 * board filter (#59), so an empty tab says no task matches.
 */
object BoardRows {
    fun of(board: Board, tab: Section, filtered: Boolean = false): List<BoardRow> {
        val content = board.section(tab)
        if (content.totalCount <= 0 && content.items.isEmpty()) return listOf(BoardRow.Empty(tab, filtered))
        val rows: List<BoardRow> = content.items.map { BoardRow.Item(it, content.section) }
        return if (content.hiddenCount > 0) rows + BoardRow.More(tab, content.hiddenCount) else rows
    }
}
