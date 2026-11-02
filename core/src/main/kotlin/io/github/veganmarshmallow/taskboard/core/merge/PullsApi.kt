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

    /**
     * Every GitHub Actions run on [sha], following the `Link` header up to [MAX_RUN_PAGES] pages
     * (only api.github.com links are followed, so the token never leaves GitHub). Runs seen twice
     * (a new run shifting the pages) are kept once. If GitHub's `total_count` says runs are missing
     * after the last page, this refuses rather than judge on part of them. A 403/404 here means the
     * token lacks Actions: Read-only ([MergeProblem.NoActionsAccess]): the PR itself was readable.
     */
    fun workflowRuns(repo: Repo, sha: String): MergeStep<List<WorkflowRun>> {
        val runs = LinkedHashMap<Any, WorkflowRun>()
        var request: HttpRequest = try {
            api.requests.workflowRuns(repo, sha)
        } catch (e: IllegalArgumentException) {
            return MergeStep.Stop(MergeProblem.Failed(BoardError.Unexpected("bad commit SHA")))
        }
        repeat(MAX_RUN_PAGES) {
            val step = read(repo, { request }, { MergeErrors.forActions(it, api.clock.instant(), repo) }) {
                it to PullJsonParser.parseWorkflowRuns(it.body)
            }
            val (response, page) = when (step) {
                is MergeStep.Stop -> return step
                is MergeStep.Ok -> step.value
            }
            page.runs.forEach { run -> runs.putIfAbsent(run.id ?: Any(), run) }
            val next = LinkHeader.nextUrl(response.header("link"))
            if (next == null) {
                val total = page.totalCount ?: return MergeStep.Ok(runs.values.toList())
                return if (runs.size >= total) MergeStep.Ok(runs.values.toList())
                else MergeStep.Stop(MergeProblem.Failed(BoardError.Unexpected("GitHub listed $total workflow runs but sent ${runs.size}")))
            }
            request = try {
                api.requests.page(next)
            } catch (e: IllegalArgumentException) {
                return MergeStep.Stop(MergeProblem.Failed(BoardError.Unexpected("bad pagination link")))
            }
        }
        return MergeStep.Stop(MergeProblem.Failed(BoardError.Unexpected("more than ${MAX_RUN_PAGES * 100} workflow runs")))
    }

    /** Commits on [base] that [sha] lacks (`behind_by` of the compare API, #45). */
    fun behindBy(repo: Repo, base: String, sha: String): MergeStep<Long> = read(repo, { api.requests.compare(repo, base, sha) }) {
        PullJsonParser.behindBy(it.body)
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

    private fun <T> read(repo: Repo, build: () -> HttpRequest, parse: (HttpResponse) -> T): MergeStep<T> =
        read(repo, build, { MergeErrors.forRead(it, api.clock.instant(), repo) }, parse)

    private fun <T> read(
        repo: Repo,
        build: () -> HttpRequest,
        errors: (HttpResponse) -> MergeProblem?,
        parse: (HttpResponse) -> T,
    ): MergeStep<T> {
        val request = try {
            build()
        } catch (e: IllegalArgumentException) {
            return MergeStep.Stop(MergeProblem.Failed(BoardError.Unexpected("bad repo name")))
        }
        val response = when (val sent = api.send(request) { null }) {
            is Outcome.Failure -> return MergeStep.Stop(MergeProblem.Failed(sent.error))
            is Outcome.Success -> sent.value
        }
        errors(response)?.let { return MergeStep.Stop(it) }
        return try {
            MergeStep.Ok(parse(response))
        } catch (e: MalformedResponseException) {
            MergeStep.Stop(MergeProblem.Failed(BoardError.Unexpected(e.message ?: "malformed JSON")))
        }
    }

    companion object {
        const val MAX_RUN_PAGES = 3
    }
}
