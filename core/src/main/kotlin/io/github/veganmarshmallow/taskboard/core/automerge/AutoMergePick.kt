package io.github.veganmarshmallow.taskboard.core.automerge

import io.github.veganmarshmallow.taskboard.core.merge.MergeCandidate
import io.github.veganmarshmallow.taskboard.core.merge.Readiness
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllPlanner
import io.github.veganmarshmallow.taskboard.core.mergeall.PlannedMerge

/**
 * Which PR auto merge (#79) takes on this poll: the first one in Merge all's order ([MergeAllPlanner])
 * among Claude's `claude/…` PRs, and only if that one is ready itself. A stacked PR is never taken
 * (its base PR goes first; once that is merged, the update workflow retargets it). One per poll.
 */
object AutoMergePick {
    const val BRANCH_PREFIX = "claude/"

    fun next(candidates: List<MergeCandidate>): PlannedMerge? {
        val ours = candidates.filter { it.pr?.headRef?.startsWith(BRANCH_PREFIX) == true }
        val first = MergeAllPlanner.order(ours).firstOrNull() ?: return null
        val candidate = ours.first { it.issue == first.issue }
        return first.takeIf { candidate.readiness is Readiness.Ready && candidate.pr?.isStacked == false }
    }
}
