package io.github.veganmarshmallow.taskboard.core.automerge

import io.github.veganmarshmallow.taskboard.core.merge.MergeCandidate
import io.github.veganmarshmallow.taskboard.core.merge.Readiness
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllPlanner
import io.github.veganmarshmallow.taskboard.core.mergeall.PlannedMerge

/**
 * Which PR auto merge (#79) takes on this poll: the first one in Merge all's order ([MergeAllPlanner])
 * among Claude's `claude/…` PRs, and only if that one is ready itself. A stacked PR is never taken
 * (its base PR goes first; once that is merged, the update workflow retargets it). One per poll in
 * Save usage; #83 Faster takes the others that are ready too, in the same order ([inOrder]).
 */
object AutoMergePick {
    const val BRANCH_PREFIX = "claude/"

    fun next(candidates: List<MergeCandidate>): PlannedMerge? = inOrder(candidates).firstOrNull()

    /** Every ready, unstacked `claude/…` PR in Merge all's order; empty unless the first in that order is one of them. */
    fun inOrder(candidates: List<MergeCandidate>): List<PlannedMerge> {
        val ours = candidates.filter { it.pr?.headRef?.startsWith(BRANCH_PREFIX) == true }
        val order = MergeAllPlanner.order(ours)
        val first = order.firstOrNull() ?: return emptyList()
        val ready = order.filter { planned ->
            val candidate = ours.first { it.issue == planned.issue }
            candidate.readiness is Readiness.Ready && candidate.pr?.isStacked == false
        }
        return if (ready.firstOrNull() == first) ready else emptyList()
    }
}
