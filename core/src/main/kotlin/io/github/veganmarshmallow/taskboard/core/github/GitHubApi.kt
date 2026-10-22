package io.github.veganmarshmallow.taskboard.core.github

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.error.ErrorClassifier
import io.github.veganmarshmallow.taskboard.core.http.HttpClient
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Repo
import java.io.IOException
import java.time.Clock
import java.time.Duration

/** The board's use of the GitHub REST API: load issues, approve a task, check a token. */
class GitHubApi(
    private val http: HttpClient,
    token: String,
    private val clock: Clock = Clock.systemUTC(),
    repo: Repo = Repo.BOARD,
    private val maxPages: Int = PagedFetcher.DEFAULT_MAX_PAGES,
) {
    private val requests = GitHubRequests(repo, token)

    /** Open issues plus issues closed as done within [doneWindow]. PRs are still included here; the board drops them. */
    fun fetchIssues(doneWindow: Duration = Duration.ofDays(7)): Outcome<List<Issue>> {
        val open = fetchAll(requests.openIssues())
        if (open is Outcome.Failure) return open
        val done = fetchAll(requests.recentlyDone(clock.instant().minus(doneWindow)))
        if (done is Outcome.Failure) return done
        return Outcome.Success((open as Outcome.Success).value + (done as Outcome.Success).value)
    }

    fun approve(issueNumber: Int): Outcome<Unit> = send(requests.approve(issueNumber)).map { }

    /** Checks the token can read the repository and its issues. */
    fun testConnection(): Outcome<Unit> {
        val repoCheck = send(requests.repository())
        if (repoCheck is Outcome.Failure) return repoCheck
        return send(requests.openIssues()).map { }
    }

    private fun fetchAll(first: HttpRequest): Outcome<List<Issue>> =
        PagedFetcher(maxPages, requests::page, ::sendPage).fetchAll(first).map { it.issues }

    private fun sendPage(request: HttpRequest): Outcome<PagedFetcher.Page> = when (val r = send(request)) {
        is Outcome.Failure -> r
        is Outcome.Success -> try {
            Outcome.Success(PagedFetcher.Page(IssueJsonParser.parsePage(r.value.body), r.value.header("link")))
        } catch (e: MalformedResponseException) {
            Outcome.Failure(BoardError.Unexpected(e.message ?: "malformed JSON"))
        }
    }

    private fun send(request: HttpRequest): Outcome<HttpResponse> {
        val response = try {
            http.execute(request)
        } catch (e: IOException) {
            return Outcome.Failure(BoardError.Network)
        }
        return ErrorClassifier.classify(response, clock.instant())?.let { Outcome.Failure(it) }
            ?: Outcome.Success(response)
    }
}
