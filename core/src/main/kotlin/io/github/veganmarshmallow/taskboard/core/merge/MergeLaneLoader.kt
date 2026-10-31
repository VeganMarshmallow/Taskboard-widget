package io.github.veganmarshmallow.taskboard.core.merge

import io.github.veganmarshmallow.taskboard.core.board.MergeLane
import io.github.veganmarshmallow.taskboard.core.board.MergeLaneSource
import io.github.veganmarshmallow.taskboard.core.board.MergeWait
import io.github.veganmarshmallow.taskboard.core.board.Placement
import io.github.veganmarshmallow.taskboard.core.board.TitleCleaner
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Repo
import io.github.veganmarshmallow.taskboard.core.work.RefreshRunner
import io.github.veganmarshmallow.taskboard.core.work.TimeLimit
import io.github.veganmarshmallow.taskboard.core.work.Timed
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The merge-next step of a refresh (#45): for every task offering Merge, finds its PR among its
 * repo's open PRs (one list request per repo), checks it ([MergeReadiness]) and ranks them
 * ([MergeNext]). It runs within [budget], a slice of the refresh's own, and never past [deadline]
 * (the refresh's own limit less a margin), so a slow board fetch is never starved by this step:
 * when time runs out, or anything throws, every row reads "can't check" and the board is still shown.
 */
class MergeLaneLoader(
    private val budget: Duration = BUDGET,
    private val limit: TimeLimit = TimeLimit(),
    private val board: Repo = Repo.BOARD,
    private val clock: Clock = Clock.systemUTC(),
    private val deadline: Instant? = null,
) : MergeLaneSource {
    override fun lanes(issues: List<Issue>, api: GitHubApi): Map<Int, MergeLane> {
        val tasks = issues.filter(Placement::canMerge).distinctBy { it.number }
        if (tasks.isEmpty()) return emptyMap()
        val left = deadline?.let { minOf(budget, Duration.between(clock.instant(), it)) } ?: budget
        val result = if (left.isNegative || left.isZero) Timed.TimedOut else limit.run(left) { candidates(tasks, api) }
        return when (result) {
            is Timed.Done -> MergeNext.lanes(result.value)
            else -> tasks.associate { it.number to MergeLane(isNext = false, wait = MergeWait.UNKNOWN, firstTitle = null) }
        }
    }

    private fun candidates(tasks: List<Issue>, api: GitHubApi): List<MergeCandidate> {
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
                is MergeStep.Ok -> MergeCandidate(task.number, title, found.value.pr.number, readiness.of(found.value))
            }
        }
    }

    companion object {
        /** Part of the refresh's 50 s; a handful of PRs takes a few requests each. */
        val BUDGET: Duration = Duration.ofSeconds(20)

        /** Left of [RefreshRunner.RUN_BUDGET] for building and saving the board after this step. */
        val MARGIN: Duration = Duration.ofSeconds(5)

        /** For a refresh starting now under [RefreshRunner]: ends by the refresh's limit less [MARGIN]. */
        fun forRefresh(clock: Clock): MergeLaneLoader =
            MergeLaneLoader(clock = clock, deadline = clock.instant().plus(RefreshRunner.RUN_BUDGET).minus(MARGIN))
    }
}
