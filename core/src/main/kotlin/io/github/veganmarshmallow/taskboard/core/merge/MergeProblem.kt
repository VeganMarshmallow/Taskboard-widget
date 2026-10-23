package io.github.veganmarshmallow.taskboard.core.merge

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.model.Repo

/** Why a task's PR can't be merged from the widget (now, or at all). [pr] is null when no PR was settled on. */
sealed class MergeProblem {
    open val pr: PullRequest? get() = null

    /** No open PR for the task was found in its comments or its repo. */
    data object NotFound : MergeProblem()

    /** Several open PRs fit and none stands out: the owner picks on GitHub. */
    data class Ambiguous(val candidates: List<PullRequest>) : MergeProblem()

    data class AlreadyMerged(override val pr: PullRequest) : MergeProblem()
    data class Closed(override val pr: PullRequest) : MergeProblem()
    data class Draft(override val pr: PullRequest) : MergeProblem()

    /** The base is another branch, not the default one. [blocker] is the open PR whose head is that branch, if known. */
    data class Stacked(override val pr: PullRequest, val blocker: Int?) : MergeProblem()

    /** `mergeable` is false, or GitHub refused with 405. [detail] is GitHub's message, if any. */
    data class Conflicts(override val pr: PullRequest, val detail: String? = null) : MergeProblem()

    /** GitHub was still computing `mergeable` after several polls. */
    data class MergeabilityUnknown(override val pr: PullRequest) : MergeProblem()

    data class ChecksRunning(override val pr: PullRequest, val names: List<String>) : MergeProblem()
    data class ChecksFailed(override val pr: PullRequest, val names: List<String>) : MergeProblem()

    /** No check run passed: none reported, or all were neutral/skipped. */
    data class NoChecks(override val pr: PullRequest) : MergeProblem()

    /** 409: the PR got new commits after it was checked. */
    data class HeadChanged(override val pr: PullRequest) : MergeProblem()

    /** 403/404 from the project repo: the token can't read or merge its PRs. */
    data class NoRepoAccess(val repo: Repo, override val pr: PullRequest? = null) : MergeProblem()

    /** Anything else, using the board's error classification (network, timeout, rate limit, 401, 5xx...). */
    data class Failed(val error: BoardError) : MergeProblem()
}

/** One step of finding, checking or merging a PR: a value, or the problem that stops the flow. */
sealed class MergeStep<out T> {
    data class Ok<T>(val value: T) : MergeStep<T>()
    data class Stop(val problem: MergeProblem) : MergeStep<Nothing>()

    inline fun <R> then(next: (T) -> MergeStep<R>): MergeStep<R> = when (this) {
        is Ok -> next(value)
        is Stop -> this
    }
}
