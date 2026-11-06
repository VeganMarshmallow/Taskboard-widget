package io.github.veganmarshmallow.taskboard.core.mergeall

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStop.Kind

/** Maps the merge package's problems onto the reasons a Merge all run stops (#57). */
object MergeAllStops {
    fun of(problem: MergeProblem): MergeAllStop = when (problem) {
        is MergeProblem.Conflicts -> MergeAllStop(Kind.CONFLICT, problem.detail)
        is MergeProblem.ChecksFailed -> MergeAllStop(Kind.CHECKS_FAILED, problem.names.joinToString(", "))
        is MergeProblem.ChecksRunning -> MergeAllStop(Kind.CHECKS_TIMEOUT, problem.names.joinToString(", "))
        is MergeProblem.ChecksAwaitingApproval -> MergeAllStop(Kind.CHECKS_APPROVAL, problem.names.joinToString(", "))
        is MergeProblem.NoChecks -> MergeAllStop(Kind.NO_CHECKS)
        is MergeProblem.MergeabilityUnknown -> MergeAllStop(Kind.ERROR, "GitHub couldn't tell whether it can be merged")
        is MergeProblem.HeadChanged -> MergeAllStop(Kind.PR_CHANGED)
        is MergeProblem.AlreadyMerged, is MergeProblem.Closed -> MergeAllStop(Kind.PR_CLOSED)
        is MergeProblem.Draft -> MergeAllStop(Kind.DRAFT)
        is MergeProblem.Stacked -> MergeAllStop(Kind.STACKED, problem.pr.baseRef)
        is MergeProblem.NoRepoAccess -> MergeAllStop(Kind.NO_ACCESS)
        is MergeProblem.NoActionsAccess -> MergeAllStop(Kind.NO_ACTIONS_ACCESS)
        is MergeProblem.Failed -> of(problem.error)
        MergeProblem.NotFound, is MergeProblem.NotLinked, is MergeProblem.Ambiguous -> MergeAllStop(Kind.PR_CLOSED)
    }

    fun of(error: BoardError): MergeAllStop = when (error) {
        is BoardError.RateLimited -> MergeAllStop(Kind.RATE_LIMITED)
        BoardError.NoToken, is BoardError.BadToken, BoardError.NoAccess -> MergeAllStop(Kind.NO_TOKEN)
        BoardError.Network, BoardError.Unreachable, BoardError.Timeout, is BoardError.Server -> MergeAllStop(Kind.NETWORK)
        is BoardError.IssueGone -> MergeAllStop(Kind.ERROR, "issue #${error.number} is gone")
        is BoardError.Unexpected -> MergeAllStop(Kind.ERROR, error.detail)
    }

    /** Trouble that may clear by itself (network, timeout, GitHub 5xx): a wait goes on through it. */
    fun transient(problem: MergeProblem): Boolean = problem is MergeProblem.Failed && when (problem.error) {
        BoardError.Network, BoardError.Unreachable, BoardError.Timeout, is BoardError.Server -> true
        else -> false
    }

    /**
     * A wait that may end by itself: checks running or not registered yet, `mergeable` still being
     * computed, or a run held for approval (#64: the build the update workflow dispatches replaces it
     * once it passes, and that run may not be registered yet).
     */
    fun stillWaiting(problem: MergeProblem): Boolean =
        problem is MergeProblem.ChecksRunning || problem is MergeProblem.NoChecks || problem is MergeProblem.MergeabilityUnknown ||
            problem is MergeProblem.ChecksAwaitingApproval
}
