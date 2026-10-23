package io.github.veganmarshmallow.taskboard.core.merge

/** A PR that may belong to the task: [linked] from its comments, [match] from its body. */
data class PrCandidate(val pr: PullRequest, val linked: Boolean, val match: TaskMatch)

/**
 * Picks the task's PR among the candidates (task #30):
 *
 * 1. Open PRs whose body says `Closes <board>#<n>` win. Only when there are none, open PRs linked
 *    from the task's comments or mentioning the task count instead.
 * 2. One left: that one (even if stacked; the eligibility check then explains why it can't merge).
 * 3. Several: the single one whose base is its repo's default branch. Still several, or none
 *    on the default branch: [MergeProblem.Ambiguous], and the owner picks on GitHub.
 * 4. None open: a merged candidate means "already merged", a closed one "closed"; otherwise not found.
 */
object PrSelector {
    fun select(candidates: Collection<PrCandidate>): MergeStep<PullRequest> {
        val relevant = candidates.filter { it.linked || it.match != TaskMatch.NONE }
        val open = relevant.filter { it.pr.isOpen }
        val pool = open.filter { it.match == TaskMatch.EXACT }.ifEmpty { open }.map { it.pr }.distinctBy { it.ref }
        return when {
            pool.size == 1 -> MergeStep.Ok(pool.single())
            pool.size > 1 -> pool.filter { !it.isStacked && it.defaultBranch != null }.singleOrNull()
                ?.let { MergeStep.Ok(it) } ?: MergeStep.Stop(MergeProblem.Ambiguous(pool))
            else -> closedOutcome(relevant.map { it.pr })
        }
    }

    private fun closedOutcome(prs: List<PullRequest>): MergeStep<Nothing> {
        prs.firstOrNull { it.isMerged }?.let { return MergeStep.Stop(MergeProblem.AlreadyMerged(it)) }
        prs.firstOrNull { !it.isOpen }?.let { return MergeStep.Stop(MergeProblem.Closed(it)) }
        return MergeStep.Stop(MergeProblem.NotFound)
    }
}
