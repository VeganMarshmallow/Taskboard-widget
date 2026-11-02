package io.github.veganmarshmallow.taskboard.core.github

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.error.ErrorClassifier
import io.github.veganmarshmallow.taskboard.core.http.HttpClient
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Repo
import io.github.veganmarshmallow.taskboard.core.task.CreateErrors
import io.github.veganmarshmallow.taskboard.core.task.CreatedIssue
import io.github.veganmarshmallow.taskboard.core.task.IssueResponseParser
import io.github.veganmarshmallow.taskboard.core.task.NewIssue
import io.github.veganmarshmallow.taskboard.core.task.RecentIssue
import io.github.veganmarshmallow.taskboard.core.task.RepoSuggestions
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The board's use of the GitHub REST API: load issues, approve a task, check a token. */
class GitHubApi(
    private val http: HttpClient,
    token: String,
    internal val clock: Clock = Clock.systemUTC(),
    repo: Repo = Repo.BOARD,
    private val maxPages: Int = PagedFetcher.DEFAULT_MAX_PAGES,
) {
    internal val requests = GitHubRequests(repo, token)

    /** Open issues plus issues closed as done within [doneWindow]. PRs are still included here; the board drops them. */
    fun fetchIssues(doneWindow: Duration = Duration.ofDays(7)): Outcome<List<Issue>> =
        fetchAll(requests.openIssues()).flatMap { open ->
            fetchAll(requests.recentlyDone(clock.instant().minus(doneWindow))).map { done -> open + done }
        }

    fun approve(issueNumber: Int): Outcome<Unit> =
        send(requests.approve(issueNumber)) { ErrorClassifier.classifyApprove(it, clock.instant(), issueNumber) }.map { }

    /** Creates a task issue. Never retried: a lost response may still mean the issue exists. */
    fun createIssue(issue: NewIssue): Outcome<CreatedIssue> =
        send(requests.createIssue(issue)) { CreateErrors.classify(it, clock.instant()) }
            .map { IssueResponseParser.created(it.body, issue.labels) }

    /** Repo suggestions from the newest 100 issues' bodies (one request, no pagination). */
    fun repoSuggestions(): Outcome<List<String>> =
        send(requests.recentIssues()).map { RepoSuggestions.fromBodies(IssueResponseParser.issueBodies(it.body)) }

    /** One page (newest created first) of issues updated since [since], for [io.github.veganmarshmallow.taskboard.core.task.SubmitRecovery]. */
    fun issuesUpdatedSince(since: Instant): Outcome<List<RecentIssue>> =
        send(requests.issuesUpdatedSince(since)).map { IssueResponseParser.recentIssues(it.body) }

    /** Checks the token can read the repository and its issues. */
    fun testConnection(): Outcome<Unit> =
        send(requests.repository()).flatMap { send(requests.openIssues()) }.map { }

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

    internal fun send(
        request: HttpRequest,
        classify: (HttpResponse) -> BoardError? = { ErrorClassifier.classify(it, clock.instant()) },
    ): Outcome<HttpResponse> {
        val response = try {
            http.execute(request)
        } catch (e: SocketTimeoutException) {
            return Outcome.Failure(BoardError.Timeout) // connected, or tried to, but GitHub never answered in time
        } catch (e: IOException) {
            return Outcome.Failure(BoardError.Network)
        } catch (e: RuntimeException) {
            // e.g. SecurityException or a platform bug inside the HTTP stack; never crash the caller.
            return Outcome.Failure(BoardError.Unexpected("request failed: ${e.javaClass.simpleName}"))
        }
        return classify(response)?.let { Outcome.Failure(it) } ?: Outcome.Success(response)
    }
}
