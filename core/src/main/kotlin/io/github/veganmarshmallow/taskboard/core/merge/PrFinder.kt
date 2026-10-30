package io.github.veganmarshmallow.taskboard.core.merge

import io.github.veganmarshmallow.taskboard.core.model.Repo
import io.github.veganmarshmallow.taskboard.core.tags.RepoField

/** The chosen PR, plus its repo's open PRs when they were already listed (to name a stacked PR's blocker). */
data class FoundPr(val pr: PullRequest, val openInRepo: List<PullRequest>?)

/**
 * Finds the open PR for task #n: PRs linked from its comments (fetched one by one), and the open
 * PRs of its Repo field's repo whose body refers to the task. [PrSelector] then picks one.
 *
 * An access error on one source does not stop the search, since another source may still find the
 * PR; it is reported only when nothing was found. Network, timeout, rate-limit and server errors
 * stop at once.
 */
class PrFinder(private val pulls: PullsApi, private val board: Repo = Repo.BOARD) {
    fun find(issue: Int, issueBody: String, comments: List<String>): MergeStep<FoundPr> {
        val repo = RepoField.repo(issueBody)
        val candidates = LinkedHashMap<PullRef, PrCandidate>()
        val lists = HashMap<Repo, List<PullRequest>>()
        var access: MergeProblem? = null

        fun add(pr: PullRequest, linked: Boolean) {
            val match = ClosesReference.match(pr.body, board, issue)
            val old = candidates[pr.ref]
            candidates[pr.ref] = PrCandidate(old?.pr ?: pr, linked || old?.linked == true, maxOf(match, old?.match ?: TaskMatch.NONE))
        }

        for (ref in PrLinks.fromComments(comments, listOfNotNull(repo))) {
            when (val step = pulls.pull(ref)) {
                is MergeStep.Ok -> add(step.value, linked = true)
                is MergeStep.Stop -> if (step.problem is MergeProblem.NoRepoAccess) access = access ?: step.problem else return step
            }
        }
        if (repo != null) {
            when (val step = pulls.openPulls(repo)) {
                is MergeStep.Ok -> {
                    lists[repo] = step.value
                    step.value.forEach { if (ClosesReference.match(it.body, board, issue) != TaskMatch.NONE) add(it, linked = false) }
                }
                is MergeStep.Stop -> if (step.problem is MergeProblem.NoRepoAccess) access = access ?: step.problem else return step
            }
        }
        return when (val chosen = PrSelector.select(candidates.values)) {
            is MergeStep.Ok -> MergeStep.Ok(FoundPr(chosen.value, lists[chosen.value.repo]))
            is MergeStep.Stop -> MergeStep.Stop(if (chosen.problem == MergeProblem.NotFound) access ?: chosen.problem else chosen.problem)
        }
    }
}
