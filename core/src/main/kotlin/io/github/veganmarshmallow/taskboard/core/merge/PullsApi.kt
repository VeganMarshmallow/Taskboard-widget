package io.github.veganmarshmallow.taskboard.core.merge

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.github.LinkHeader
import io.github.veganmarshmallow.taskboard.core.github.MalformedResponseException
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.json.string
import io.github.veganmarshmallow.taskboard.core.model.Repo

/** Typed project-repo calls for merging, through [GitHubApi]'s request pipeline (timeouts, network errors). */
class PullsApi(private val api: GitHubApi) {
    fun pull(ref: PullRef): MergeStep<PullRequest> =
        read(ref.repo, { api.requests.pull(ref) }) { PullJsonParser.parsePull(it.body, ref.repo) }

    fun openPulls(repo: Repo): MergeStep<List<PullRequest>> =
        read(repo, { api.requests.openPulls(repo) }) { PullJsonParser.parsePulls(it.body, repo) }

    fun defaultBranch(repo: Repo): MergeStep<String> = read(repo, { api.requests.repository(repo) }) { response ->
        val parsed = try {
            JsonParser.parse(response.body) as? Map<*, *>
        } catch (e: Exception) {
            null
        }
        parsed?.string("default_branch")?.takeIf { it.isNotEmpty() } ?: throw MalformedResponseException("Repo without a default branch")
    }

    /** Every check run on [sha], following pagination up to [MAX_CHECK_PAGES] pages. */
    fun checkRuns(repo: Repo, sha: String): MergeStep<List<CheckRun>> {
        val runs = ArrayList<CheckRun>()
        var request: HttpRequest = try {
            api.requests.checkRuns(repo, sha)
        } catch (e: IllegalArgumentException) {
            return MergeStep.Stop(MergeProblem.Failed(BoardError.Unexpected("bad commit SHA")))
        }
        repeat(MAX_CHECK_PAGES) {
            val page = when (val step = read(repo, { request }) { it to PullJsonParser.parseCheckRuns(it.body) }) {
                is MergeStep.Stop -> return step
                is MergeStep.Ok -> step.value
            }
            runs += page.second.runs
            val next = LinkHeader.nextUrl(page.first.header("link")) ?: return MergeStep.Ok(runs)
            request = try {
                api.requests.page(next)
            } catch (e: IllegalArgumentException) {
                return MergeStep.Stop(MergeProblem.Failed(BoardError.Unexpected("bad pagination link")))
            }
        }
        return MergeStep.Stop(MergeProblem.Failed(BoardError.Unexpected("more than ${MAX_CHECK_PAGES * 100} check runs")))
    }

    /** Merges [pr] at its checked head SHA. Never retried: a lost response may still mean it merged. */
    fun merge(pr: PullRequest): MergeStep<Unit> {
        val request = try {
            api.requests.mergePull(pr)
        } catch (e: IllegalArgumentException) {
            return MergeStep.Stop(MergeProblem.Failed(BoardError.Unexpected("bad PR")))
        }
        return when (val sent = api.send(request) { null }) {
            is Outcome.Failure -> MergeStep.Stop(MergeProblem.Failed(sent.error))
            is Outcome.Success -> MergeErrors.forMerge(sent.value, api.clock.instant(), pr)?.let { MergeStep.Stop(it) } ?: MergeStep.Ok(Unit)
        }
    }

    private fun <T> read(repo: Repo, build: () -> HttpRequest, parse: (HttpResponse) -> T): MergeStep<T> {
        val request = try {
            build()
        } catch (e: IllegalArgumentException) {
            return MergeStep.Stop(MergeProblem.Failed(BoardError.Unexpected("bad repo name")))
        }
        val response = when (val sent = api.send(request) { null }) {
            is Outcome.Failure -> return MergeStep.Stop(MergeProblem.Failed(sent.error))
            is Outcome.Success -> sent.value
        }
        MergeErrors.forRead(response, api.clock.instant(), repo)?.let { return MergeStep.Stop(it) }
        return try {
            MergeStep.Ok(parse(response))
        } catch (e: MalformedResponseException) {
            MergeStep.Stop(MergeProblem.Failed(BoardError.Unexpected(e.message ?: "malformed JSON")))
        }
    }

    companion object {
        const val MAX_CHECK_PAGES = 3
    }
}
