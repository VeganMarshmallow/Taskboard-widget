package io.github.veganmarshmallow.taskboard.core.mergeall

import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.board.MergeWait
import io.github.veganmarshmallow.taskboard.core.board.Section

/**
 * When the widget offers Merge all (#57): from the lanes the last refresh worked out (#45), at least
 * [MIN] PRs could go in one run (a PR to merge next, plus ready or stacked ones). The plan itself
 * is worked out again, fresh, when the button is tapped.
 */
object MergeAllOffer {
    const val MIN = 2

    fun count(board: Board?): Int {
        val lanes = board?.section(Section.NEEDS_YOU)?.items?.mapNotNull { it.mergeLane }.orEmpty()
        if (lanes.none { it.isNext }) return 0
        return lanes.count { it.isNext || it.wait == MergeWait.QUEUED || it.wait == MergeWait.STACKED }
    }

    fun shows(board: Board?): Boolean = count(board) >= MIN
}
