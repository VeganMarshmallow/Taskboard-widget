package io.github.veganmarshmallow.taskboard.core.merge

import java.time.Duration

/**
 * The pre-merge checks (task #30), in this order: the PR is open, not merged and not a draft; its
 * base is the repo's default branch (a stacked PR would merge into another PR's branch, the
 * mistake that put PR #3 on the wrong branch); `mergeable` is true; the head commit's check runs
 * passed ([CheckRuns]). GitHub computes `mergeable` lazily, so a null is re-polled up to [polls]
 * times, waiting [pollDelay] × attempt between tries.
 */
class MergeChecker(
    private val pulls: PullsApi,
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
    private val polls: Int = 3,
    private val pollDelay: Duration = Duration.ofMillis(1500),
) {
    init {
        require(polls >= 1) { "polls must be at least 1" }
    }

    /** The PR as it stands now, with the head SHA that was checked; merge exactly that SHA. */
    fun check(found: FoundPr): MergeStep<PullRequest> = withDefaultBranch(found.pr).then { first ->
        refuse(first, found.openInRepo) ?: poll(first).then { fresh ->
            val current = fresh.copy(defaultBranch = fresh.defaultBranch ?: first.defaultBranch)
            refuse(current, found.openInRepo) ?: mergeable(current).then(::checks)
        }
    }

    /**
     * Right before the merge PUT, after the owner confirmed: the PR is fetched once more and refused
     * if it was merged, closed, turned into a draft or retargeted onto another branch meanwhile, or
     * got new commits. The PUT is also pinned to the SHA, but a base change is not covered by that.
     * A `mergeable` still being computed is left to GitHub, which refuses a non-mergeable PR itself.
     */
    fun recheck(checked: PullRequest): MergeStep<PullRequest> = pulls.pull(checked.ref).then { fresh ->
        val current = fresh.copy(defaultBranch = fresh.defaultBranch ?: checked.defaultBranch)
        refuse(current, null) ?: when {
            current.headSha != checked.headSha -> MergeStep.Stop(MergeProblem.HeadChanged(checked))
            current.mergeable == false -> MergeStep.Stop(MergeProblem.Conflicts(current))
            else -> MergeStep.Ok(checked)
        }
    }

    private fun withDefaultBranch(pr: PullRequest): MergeStep<PullRequest> =
        if (pr.defaultBranch != null) MergeStep.Ok(pr) else pulls.defaultBranch(pr.repo).then { MergeStep.Ok(pr.copy(defaultBranch = it)) }

    private fun refuse(pr: PullRequest, openInRepo: List<PullRequest>?): MergeStep.Stop? = refusal(pr, openInRepo)?.let { MergeStep.Stop(it) }

    private fun refusal(pr: PullRequest, openInRepo: List<PullRequest>?): MergeProblem? = when {
        pr.isMerged -> MergeProblem.AlreadyMerged(pr)
        !pr.isOpen -> MergeProblem.Closed(pr)
        pr.isDraft -> MergeProblem.Draft(pr)
        pr.isStacked -> MergeProblem.Stacked(pr, blocker(pr, openInRepo))
        else -> null
    }

    /** The open PR whose head branch is [pr]'s base: the one to merge first. */
    private fun blocker(pr: PullRequest, openInRepo: List<PullRequest>?): Int? {
        val open = openInRepo ?: (pulls.openPulls(pr.repo) as? MergeStep.Ok)?.value ?: return null
        return open.firstOrNull { it.headRef == pr.baseRef && it.number != pr.number }?.number
    }

    private fun poll(pr: PullRequest): MergeStep<PullRequest> {
        var current = pr
        for (attempt in 0 until polls) {
            if (attempt > 0) sleep(pollDelay.multipliedBy(attempt.toLong()))
            current = when (val step = pulls.pull(pr.ref)) {
                is MergeStep.Stop -> return step
                is MergeStep.Ok -> step.value
            }
            if (current.mergeable != null) break
        }
        return MergeStep.Ok(current)
    }

    private fun mergeable(pr: PullRequest): MergeStep<PullRequest> = when (pr.mergeable) {
        true -> MergeStep.Ok(pr)
        false -> MergeStep.Stop(MergeProblem.Conflicts(pr))
        null -> MergeStep.Stop(MergeProblem.MergeabilityUnknown(pr))
    }

    private fun checks(pr: PullRequest): MergeStep<PullRequest> = pulls.checkRuns(pr.repo, pr.headSha).then { runs ->
        when (val verdict = CheckRuns.verdict(runs)) {
            ChecksVerdict.Passed -> MergeStep.Ok(pr)
            is ChecksVerdict.Running -> MergeStep.Stop(MergeProblem.ChecksRunning(pr, verdict.names))
            is ChecksVerdict.Failed -> MergeStep.Stop(MergeProblem.ChecksFailed(pr, verdict.names))
            ChecksVerdict.None, ChecksVerdict.NothingPassed -> MergeStep.Stop(MergeProblem.NoChecks(pr))
        }
    }
}
