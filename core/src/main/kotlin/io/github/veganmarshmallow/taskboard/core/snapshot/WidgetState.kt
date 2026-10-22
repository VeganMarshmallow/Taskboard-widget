package io.github.veganmarshmallow.taskboard.core.snapshot

import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import java.time.Instant

/**
 * What the widget renders: the last good [board] (kept when a later refresh fails, so the widget
 * shows stale data under the error rather than nothing), the latest [error] if the most recent
 * attempt failed, and when that attempt happened.
 */
data class WidgetState(
    val board: Board?,
    val error: BoardError?,
    val attemptedAt: Instant?,
) {
    fun withBoard(newBoard: Board) = WidgetState(newBoard, null, newBoard.fetchedAt)
    fun withError(newError: BoardError, at: Instant) = WidgetState(board, newError, at)

    companion object {
        val INITIAL = WidgetState(null, null, null)
    }
}
