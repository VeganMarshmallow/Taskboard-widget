package io.github.veganmarshmallow.taskboard.core.merge

import io.github.veganmarshmallow.taskboard.core.board.MergeWait
import io.github.veganmarshmallow.taskboard.core.board.TitleCleaner
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Repo

/**
 * For every task offering Merge, finds its PR among its repo's open PRs (one list request per repo)
 * and checks it ([MergeReadiness]). Shared by the refresh's merge-next step (#45) and Merge all (#57).
 */
class MergeCandidates(private val board: Repo = Repo.BOARD) {
    fun of(tasks: List<Issue>, api: GitHubApi): List<MergeCandidate> {
        val pulls = PullsApi(api)
        val finder = PrFinder(pulls, board)
        val readiness = MergeReadiness(pulls)
        val listed = HashMap<Repo, MergeStep<List<PullRequest>>>()
        return tasks.map { task ->
            val title = TitleCleaner.clean(task.title)
            val repo = task.repo ?: return@map MergeCandidate(task.number, title, null, Readiness.Waiting(MergeWait.NO_PR))
            val found = listed.getOrPut(repo) { pulls.openPulls(repo) }.then { finder.fromOpen(task.number, repo, it) }
            when (found) {
                is MergeStep.Stop -> MergeCandidate(task.number, title, found.problem.pr?.number, Readiness.Waiting(MergeReadiness.waitFor(found.problem)))
                is MergeStep.Ok -> MergeCandidate(task.number, title, found.value.pr.number, readiness.of(found.value), found.value.pr)
            }
        }
    }
}
