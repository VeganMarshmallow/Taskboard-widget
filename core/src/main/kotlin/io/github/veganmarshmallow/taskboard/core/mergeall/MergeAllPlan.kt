package io.github.veganmarshmallow.taskboard.core.mergeall

import io.github.veganmarshmallow.taskboard.core.board.MergeWait
import io.github.veganmarshmallow.taskboard.core.board.Placement
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.merge.MergeCandidate
import io.github.veganmarshmallow.taskboard.core.merge.MergeCandidates
import io.github.veganmarshmallow.taskboard.core.merge.MergeNext
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.merge.MergeStep
import io.github.veganmarshmallow.taskboard.core.merge.PullRef
import io.github.veganmarshmallow.taskboard.core.merge.Readiness
import io.github.veganmarshmallow.taskboard.core.merge.guardedStep
import io.github.veganmarshmallow.taskboard.core.model.Repo

/** One PR of a Merge all run (#57): its task, the task's title, and the PR. */
data class PlannedMerge(val issue: Int, val title: String, val pr: PullRef)

/**
 * Merge all's order (#57), on the same candidates as merge next (#45): the ready PRs in merge-next
 * order ([MergeNext.ranked]: ready first, then oldest PR), each followed right away by the PRs stacked
 * on it, bottom-up (a stacked PR is retargeted onto the default branch once its base PR is merged).
 * A stacked PR whose base PR is not in the plan is left out, and so is every PR that is not ready.
 */
object MergeAllPlanner {
    fun order(candidates: List<MergeCandidate>): List<PlannedMerge> {
        val unique = candidates.distinctBy { it.issue }.filter { it.pr != null }
        val stacked = unique.filter { (it.readiness as? Readiness.Waiting)?.wait == MergeWait.STACKED }
        val plan = LinkedHashMap<PullRef, PlannedMerge>()
        fun visit(c: MergeCandidate) {
            val pr = c.pr ?: return
            if (pr.ref in plan) return
            plan[pr.ref] = PlannedMerge(c.issue, c.title, pr.ref)
            stacked.filter { it.pr!!.repo == pr.repo && it.pr.baseRef == pr.headRef }.sortedBy { it.pr!!.number }.forEach { visit(it) }
        }
        MergeNext.ranked(unique).forEach { visit(it) }
        return plan.values.toList()
    }
}

/** Works out a fresh plan when Merge all is tapped: the open tasks offering Merge, checked now. Never throws. */
class MergeAllPlanning(private val api: GitHubApi, private val board: Repo = Repo.BOARD) {
    fun plan(): MergeStep<List<PlannedMerge>> = guardedStep {
        when (val issues = api.fetchIssues()) {
            is Outcome.Failure -> MergeStep.Stop(MergeProblem.Failed(issues.error))
            is Outcome.Success -> {
                val tasks = issues.value.filter(Placement::canMerge).distinctBy { it.number }
                MergeStep.Ok(MergeAllPlanner.order(MergeCandidates(board).of(tasks, api)))
            }
        }
    }
}
