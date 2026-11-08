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
import io.github.veganmarshmallow.taskboard.core.pace.WorkPace

/**
 * One auto merge poll (#79), run by the widget after a refresh with the owner's token: picks the
 * next ready PR ([AutoMergePick]), checks it again right now with the Merge button's own checks
 * ([MergeAllGitHub.checks]: full `pull_request` run green, not a `[partial]` one; mergeable; not a
 * draft), makes sure it is still up to date with its default branch, and merges exactly that head.
 * Anything that clears by itself is a quiet [Skip.NOT_READY_YET].
 *
 * #83, [pace]: in Save usage, at most one merge per poll. In Faster, after a merge the poll goes on to
 * the next PR that was ready when the poll started ([AutoMergePick.inOrder]), up to [MAX_PER_POLL],
 * each checked again just as the first. Such a PR is now behind its default branch only by this poll's
 * merges; its own green run is enough (it isn't updated and re-tested first). The first stop ends it.
 */
class AutoMerger(
    private val candidates: () -> MergeStep<List<MergeCandidate>>,
    private val github: MergeAllGitHub,
    private val pace: WorkPace = WorkPace.DEFAULT,
) {
    /** The poll's first outcome (in Save usage, its only one). */
    fun poll(enabled: Boolean, mergeAllRunning: Boolean): AutoMergeOutcome = pollAll(enabled, mergeAllRunning).first()

    /** Every outcome of one poll, in order: the merges, then whatever ended it (never empty). */
    fun pollAll(enabled: Boolean, mergeAllRunning: Boolean): List<AutoMergeOutcome> {
        if (!enabled) return listOf(Skipped(Skip.DISABLED))
        if (mergeAllRunning) return listOf(Skipped(Skip.MERGE_ALL_RUNNING))
        val found = when (val listed = guardedStep(candidates)) {
            is MergeStep.Stop -> return listOf(Skipped(Skip.CANT_CHECK))
            is MergeStep.Ok -> listed.value
        }
        val ready = AutoMergePick.inOrder(found).take(if (pace.mergesBackToBack) MAX_PER_POLL else 1)
        if (ready.isEmpty()) return listOf(Skipped(Skip.NOTHING_READY))
        val outcomes = ArrayList<AutoMergeOutcome>()
        for ((i, next) in ready.withIndex()) {
            val outcome = mergeOne(next, behindAllowed = i > 0)
            outcomes += outcome
            if (outcome !is AutoMergeOutcome.Merged) break
        }
        return outcomes
    }

    private fun mergeOne(next: PlannedMerge, behindAllowed: Boolean): AutoMergeOutcome =
        when (val checked = guardedStep { recheck(next, behindAllowed) }) {
            is MergeStep.Stop -> settle(next, checked.problem)
            is MergeStep.Ok -> merge(next, checked.value)
        }

    /** The PR now: open, not merged, up to date (unless [behindAllowed]), and passing every check. */
    private fun recheck(next: PlannedMerge, behindAllowed: Boolean): MergeStep<PullRequest> = github.pull(next.pr).then { pr ->
        when {
            pr.isMerged -> MergeStep.Stop(MergeProblem.AlreadyMerged(pr))
            else -> github.behindBy(pr).then { behind ->
                if (behind > 0 && !behindAllowed) MergeStep.Stop(MergeProblem.HeadChanged(pr)) else github.checks(pr)
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
        /** Faster's most merges in one poll: a long queue goes on at the next poll, after a refresh. */
        const val MAX_PER_POLL = 5

        /** Problems the next poll may not have: waits, network trouble, a PR that moved, closed or merged meanwhile. */
        fun clearsByItself(problem: MergeProblem): Boolean = MergeAllStops.stillWaiting(problem) || MergeAllStops.transient(problem) ||
            problem is MergeProblem.HeadChanged || problem is MergeProblem.AlreadyMerged || problem is MergeProblem.Closed ||
            problem is MergeProblem.Draft || problem == MergeProblem.NotFound
    }
}
