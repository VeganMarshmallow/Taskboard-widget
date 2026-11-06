package io.github.veganmarshmallow.taskboard.core.merge

import io.github.veganmarshmallow.taskboard.core.board.MergeWait
import java.time.Instant

/** Whether a PR can be merged now (#45), and since when; or why not. */
sealed class Readiness {
    /** [since]: when its last check finished, the proxy for "ready first" (null when GitHub sent no time). */
    data class Ready(val since: Instant?) : Readiness()
    data class Waiting(val wait: MergeWait) : Readiness()
}

/**
 * The widget's merge checks, run on every refresh for each task offering Merge (#45). They are the
 * Merge button's own checks ([MergeChecker], without its waits: a `mergeable` GitHub is still
 * computing reads "updating" until the next refresh), plus "up to date with the default branch"
 * (`behind_by == 0`). Cheapest first: the `needs-rebase` label, draft and stacked need no request;
 * then the PR, its Actions runs, and only for a PR that passed all that, the compare call.
 */
class MergeReadiness(private val pulls: PullsApi) {
    private val checker = MergeChecker(pulls, sleep = {}, polls = 1)

    fun of(found: FoundPr): Readiness {
        if (found.pr.labels.any { it.equals(NEEDS_REBASE, ignoreCase = true) }) return Readiness.Waiting(MergeWait.CONFLICT_CLAUDE_FIXES)
        return when (val step = checker.evaluate(found)) {
            is MergeStep.Stop -> Readiness.Waiting(waitFor(step.problem))
            is MergeStep.Ok -> upToDate(step.value)
        }
    }

    private fun upToDate(checked: CheckedPr): Readiness {
        val pr = checked.pr
        val base = pr.defaultBranch ?: return Readiness.Waiting(MergeWait.UNKNOWN)
        return when (val behind = pulls.behindBy(pr.repo, base, pr.headSha)) {
            is MergeStep.Stop -> Readiness.Waiting(waitFor(behind.problem))
            is MergeStep.Ok -> if (behind.value == 0L) Readiness.Ready(readySince(checked.runs)) else Readiness.Waiting(MergeWait.UPDATING)
        }
    }

    companion object {
        const val NEEDS_REBASE = "needs-rebase"

        /**
         * "Ready first" without any history on the phone: the newest finish time (`updated_at`,
         * else `created_at`) among the successful runs that passed the head. A PR becomes ready
         * when its last check passes, so the one whose checks finished earliest was ready first.
         */
        fun readySince(runs: List<WorkflowRun>): Instant? = runs
            .filter { it.conclusion.equals("success", ignoreCase = true) }
            .mapNotNull { it.updatedAt ?: it.createdAt }
            .maxOrNull()

        /** The row's short reason for each way a check or lookup can stop. */
        fun waitFor(problem: MergeProblem): MergeWait = when (problem) {
            is MergeProblem.Draft -> MergeWait.DRAFT
            is MergeProblem.Stacked -> MergeWait.STACKED
            is MergeProblem.ChecksRunning -> MergeWait.CHECKS_RUNNING
            is MergeProblem.ChecksFailed -> MergeWait.CHECKS_FAILED
            is MergeProblem.ChecksAwaitingApproval -> MergeWait.CHECKS_APPROVAL
            is MergeProblem.NoChecks -> MergeWait.NO_CHECKS
            is MergeProblem.Conflicts -> MergeWait.CONFLICT
            is MergeProblem.MergeabilityUnknown, is MergeProblem.HeadChanged -> MergeWait.UPDATING
            MergeProblem.NotFound, is MergeProblem.NotLinked, is MergeProblem.Ambiguous,
            is MergeProblem.AlreadyMerged, is MergeProblem.Closed -> MergeWait.NO_PR
            is MergeProblem.NoRepoAccess, is MergeProblem.NoActionsAccess, is MergeProblem.Failed -> MergeWait.UNKNOWN
        }
    }
}
