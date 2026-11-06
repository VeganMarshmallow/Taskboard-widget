package io.github.veganmarshmallow.taskboard.core.merge

import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.model.Repo
import io.github.veganmarshmallow.taskboard.core.preview.PreviewApi

/**
 * Finds task #n's open PR: loads the issue and its recent comments, then asks [PrFinder]. Shared
 * by Merge (task #30) and "Request changes" (task #65), so both name the same PR. Blocking.
 */
class TaskPrLookup(api: GitHubApi, board: Repo = Repo.BOARD) {
    private val preview = PreviewApi(api)
    private val finder = PrFinder(PullsApi(api), board)

    fun find(issue: Int): MergeStep<FoundPr> = when (val details = preview.fetchIssue(issue)) {
        is Outcome.Failure -> MergeStep.Stop(MergeProblem.Failed(details.error))
        is Outcome.Success -> when (val comments = preview.fetchRecentComments(issue)) {
            is Outcome.Failure -> MergeStep.Stop(MergeProblem.Failed(comments.error))
            is Outcome.Success -> finder.find(issue, details.value.body, comments.value.map { it.body })
        }
    }
}
