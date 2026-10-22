package io.github.veganmarshmallow.taskboard.core.board

/**
 * Flattens a [Board] into list rows: for each non-empty section a header, its items and a
 * "+N more" row if capped; an [BoardRow.Empty] row when nothing is open; and always a final
 * [BoardRow.Done] count row.
 */
object BoardRows {
    fun of(board: Board): List<BoardRow> {
        val rows = ArrayList<BoardRow>()
        for (content in board.sections) {
            if (content.totalCount == 0) continue
            rows.add(BoardRow.Header(content.section, content.totalCount))
            content.items.mapTo(rows) { BoardRow.Item(it) }
            if (content.hiddenCount > 0) rows.add(BoardRow.More(content.section, content.hiddenCount))
        }
        if (rows.isEmpty()) rows.add(BoardRow.Empty)
        rows.add(BoardRow.Done(board.doneLast7Days))
        return rows
    }
}
