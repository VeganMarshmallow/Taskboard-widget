package io.github.veganmarshmallow.taskboard.core.merge

/** A PR that may belong to the task: [linked] from its comments, [match] from its body. */
data class PrCandidate(val pr: PullRequest, val linked: Boolean, val match: TaskMatch)

/**
 * Picks the task's PR among the candidates (task #30). Merging is a write to the owner's code, so
 * only a PR that claims the task itself is ever chosen:
 *
 * 1. Only open PRs whose body says `Closes <board>#<n>` ([TaskMatch.EXACT]) can be merged. A PR
 *    that is merely linked from a comment or mentions the task is never merged from here: when
 *    only such PRs are open, [MergeProblem.NotLinked] sends the owner to GitHub.
 * 2. One left: that one (even if stacked; the eligibility check then explains why it can't merge).
 * 3. Several in one repo: the single one whose base is the default branch. Several repos, several
 *    on the default branch, or none on it: [MergeProblem.Ambiguous], and the owner picks on GitHub.
 * 4. None open: a merged candidate means "already merged", a closed one "closed"; otherwise not found.
 *
 * PRs are told apart by repo (ignoring case, as GitHub does) and number.
 */
object PrSelector {
    fun select(candidates: Collection<PrCandidate>): MergeStep<PullRequest> {
        val relevant = candidates.filter { it.linked || it.match != TaskMatch.NONE }
        val open = relevant.filter { it.pr.isOpen }
        val pool = open.filter { it.match == TaskMatch.EXACT }.map { it.pr }.distinctBy(::key)
        return when {
            pool.size == 1 -> MergeStep.Ok(pool.single())
            pool.size > 1 -> onDefaultBranch(pool)?.let { MergeStep.Ok(it) } ?: MergeStep.Stop(MergeProblem.Ambiguous(pool))
            open.isNotEmpty() -> MergeStep.Stop(MergeProblem.NotLinked(open.map { it.pr }.distinctBy(::key)))
            else -> closedOutcome(relevant.map { it.pr })
        }
    }

    /** The single non-stacked PR, when every candidate is in the same repo. */
    private fun onDefaultBranch(pool: List<PullRequest>): PullRequest? {
        if (pool.map { it.repo.fullName.lowercase() }.distinct().size > 1) return null
        return pool.filter { !it.isStacked && it.defaultBranch != null }.singleOrNull()
    }

    private fun key(pr: PullRequest) = "${pr.repo.fullName.lowercase()}#${pr.number}"

    private fun closedOutcome(prs: List<PullRequest>): MergeStep<Nothing> {
        prs.firstOrNull { it.isMerged }?.let { return MergeStep.Stop(MergeProblem.AlreadyMerged(it)) }
        prs.firstOrNull { !it.isOpen }?.let { return MergeStep.Stop(MergeProblem.Closed(it)) }
        return MergeStep.Stop(MergeProblem.NotFound)
    }
}
