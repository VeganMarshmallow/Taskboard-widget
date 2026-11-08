package io.github.veganmarshmallow.taskboard.core.automerge

import io.github.veganmarshmallow.taskboard.core.automerge.AutoMergeOutcome.Skip
import io.github.veganmarshmallow.taskboard.core.automerge.AutoMergeOutcome.Skipped
import io.github.veganmarshmallow.taskboard.core.merge.MergeCandidate
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.merge.MergeStep
import io.github.veganmarshmallow.taskboard.core.merge.PullRequest
import io.github.veganmarshmallow.taskboard.core.merge.guardedStep
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllGitHub
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStops
import io.github.veganmarshmallow.taskboard.core.mergeall.PlannedMerge

/**
 * One auto merge poll (#79), run by the widget after a refresh with the owner's token: picks the
 * next ready PR ([AutoMergePick]), checks it again right now with the Merge button's own checks
 * ([MergeAllGitHub.checks]: full `pull_request` run green, not a `[partial]` one; mergeable; not a
 * draft), makes sure it is still up to date with its default branch, and merges exactly that head.
 * At most one merge per poll. Anything that clears by itself is a quiet [Skip.NOT_READY_YET].
 */
class AutoMerger(
    private val candidates: () -> MergeStep<List<MergeCandidate>>,
    private val github: MergeAllGitHub,
) {
    fun poll(enabled: Boolean, mergeAllRunning: Boolean): AutoMergeOutcome {
        if (!enabled) return Skipped(Skip.DISABLED)
        if (mergeAllRunning) return Skipped(Skip.MERGE_ALL_RUNNING)
        val found = when (val listed = guardedStep(candidates)) {
            is MergeStep.Stop -> return Skipped(Skip.CANT_CHECK)
            is MergeStep.Ok -> listed.value
        }
        val next = AutoMergePick.next(found) ?: return Skipped(Skip.NOTHING_READY)
        return when (val checked = guardedStep { recheck(next) }) {
            is MergeStep.Stop -> settle(next, checked.problem)
            is MergeStep.Ok -> merge(next, checked.value)
        }
    }

    /** The PR now: open, not merged, up to date, and passing every check. */
    private fun recheck(next: PlannedMerge): MergeStep<PullRequest> = github.pull(next.pr).then { pr ->
        when {
            pr.isMerged -> MergeStep.Stop(MergeProblem.AlreadyMerged(pr))
            else -> github.behindBy(pr).then { behind ->
                if (behind > 0) MergeStep.Stop(MergeProblem.HeadChanged(pr)) else github.checks(pr)
            }
        }
    }

    private fun merge(next: PlannedMerge, pr: PullRequest): AutoMergeOutcome =
        when (val merged = guardedStep { github.merge(pr) }) {
            is MergeStep.Ok -> AutoMergeOutcome.Merged(next)
            // No clear answer: the merge may have gone through. Look before calling it a skip.
            is MergeStep.Stop -> if (MergeAllStops.transient(merged.problem) && mergedNow(next)) {
                AutoMergeOutcome.Merged(next)
            } else {
                settle(next, merged.problem)
            }
        }

    private fun mergedNow(next: PlannedMerge): Boolean = (guardedStep { github.pull(next.pr) } as? MergeStep.Ok)?.value?.isMerged == true

    private fun settle(next: PlannedMerge, problem: MergeProblem): AutoMergeOutcome =
        if (clearsByItself(problem)) Skipped(Skip.NOT_READY_YET) else AutoMergeOutcome.Failed(next, MergeAllStops.of(problem))

    companion object {
        /** Problems the next poll may not have: waits, network trouble, a PR that moved, closed or merged meanwhile. */
        fun clearsByItself(problem: MergeProblem): Boolean = MergeAllStops.stillWaiting(problem) || MergeAllStops.transient(problem) ||
            problem is MergeProblem.HeadChanged || problem is MergeProblem.AlreadyMerged || problem is MergeProblem.Closed ||
            problem is MergeProblem.Draft || problem == MergeProblem.NotFound
    }
}
