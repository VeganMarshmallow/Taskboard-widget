package io.github.veganmarshmallow.taskboard.core.mergeall

import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.board.MergeLane
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.merge.MergeCandidate
import io.github.veganmarshmallow.taskboard.core.merge.MergeNext

/**
 * When the widget offers Merge all (#57): from the lanes the last refresh worked out (#45), at least
 * [MIN] PRs could go in one run. The plan itself is worked out again, fresh, when the button is tapped.
 *
 * #60: the count is the PRs [MergeAllPlanner.order] takes (the plan the confirmation lists), so a
 * stacked PR whose base PR is not in the run is not counted.
 */
object MergeAllOffer {
    const val MIN = 2

    /** The refresh's lanes ([MergeNext.lanes]), each marked with whether the plan takes its PR. */
    fun lanes(candidates: List<MergeCandidate>): Map<Int, MergeLane> =
        MergeNext.lanes(candidates, MergeAllPlanner.order(candidates).mapTo(HashSet()) { it.issue })

    fun count(board: Board?): Int {
        val lanes = board?.section(Section.NEEDS_YOU)?.items?.mapNotNull { it.mergeLane }.orEmpty()
        if (lanes.none { it.isNext }) return 0
        return lanes.count { it.inRun }
    }

    fun shows(board: Board?): Boolean = count(board) >= MIN
}
