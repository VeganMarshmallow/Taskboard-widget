package io.github.veganmarshmallow.taskboard.core.merge

import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.model.Repo
import io.github.veganmarshmallow.taskboard.core.preview.PreviewApi
import java.time.Duration

/**
 * Merging a task's PR from the widget (task #30), in two blocking steps with the owner's
 * confirmation between them: [prepare] finds the PR and runs every check, [merge] re-reads the PR
 * ([MergeChecker.recheck]) and merges exactly the head SHA that was checked. Neither throws.
 */
class MergeService(
    private val api: GitHubApi,
    sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
    board: Repo = Repo.BOARD,
) {
    private val pulls = PullsApi(api)
    private val finder = PrFinder(pulls, board)
    private val checker = MergeChecker(pulls, sleep)

    fun prepare(issue: Int): MergeStep<PullRequest> = guardedStep {
        val preview = PreviewApi(api)
        when (val details = preview.fetchIssue(issue)) {
            is Outcome.Failure -> MergeStep.Stop(MergeProblem.Failed(details.error))
            is Outcome.Success -> when (val comments = preview.fetchRecentComments(issue)) {
                is Outcome.Failure -> MergeStep.Stop(MergeProblem.Failed(comments.error))
                is Outcome.Success -> finder.find(issue, details.value.body, comments.value.map { it.body }).then(checker::check)
            }
        }
    }

    fun merge(pr: PullRequest): MergeStep<PullRequest> = guardedStep {
        checker.recheck(pr).then { pulls.merge(pr) }.then { MergeStep.Ok(pr) }
    }
}
