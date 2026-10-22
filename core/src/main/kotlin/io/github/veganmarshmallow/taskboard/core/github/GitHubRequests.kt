package io.github.veganmarshmallow.taskboard.core.github

import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.QueryEncoding.encode
import io.github.veganmarshmallow.taskboard.core.json.JsonWriter
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.model.Repo
import io.github.veganmarshmallow.taskboard.core.task.NewIssue
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Builds the REST requests the app sends. Pure: no I/O. */
class GitHubRequests(private val repo: Repo, private val token: String) {
    init {
        require(token.isNotBlank()) { "token must not be blank" }
    }

    fun openIssues(): HttpRequest = get(issuesUrl("state" to "open"))

    /**
     * Closed `status: done` issues updated since [since]. `since` filters on updated_at, which is
     * never earlier than closed_at, so this is a superset of "closed since"; callers filter exactly.
     */
    fun recentlyDone(since: Instant): HttpRequest = get(
        issuesUrl(
            "state" to "closed",
            "labels" to Labels.DONE,
            "since" to since.truncatedTo(ChronoUnit.SECONDS).toString(),
        ),
    )

    /** Follow-up page URL from a Link header. Only GitHub API URLs are accepted, so the token never leaves GitHub. */
    fun page(url: String): HttpRequest {
        require(isApiUrl(url)) { "Refusing to send credentials to a non-GitHub URL" }
        return get(url)
    }

    fun repository(): HttpRequest = get("$API/repos/${repo.fullName}")

    fun approve(issueNumber: Int): HttpRequest {
        require(issueNumber > 0) { "issue number must be positive" }
        return HttpRequest(
            method = "POST",
            url = "$API/repos/${repo.fullName}/issues/$issueNumber/labels",
            headers = headers() + ("Content-Type" to "application/json; charset=utf-8"),
            body = JsonWriter.write(mapOf("labels" to listOf(Labels.READY_FOR_CLAUDE))),
        )
    }

    /** Creates an issue (task #9). Labels the token may not apply are dropped by GitHub, not refused. */
    fun createIssue(issue: NewIssue): HttpRequest = HttpRequest(
        method = "POST",
        url = "$API/repos/${repo.fullName}/issues",
        headers = headers() + ("Content-Type" to "application/json; charset=utf-8"),
        body = JsonWriter.write(mapOf("title" to issue.title, "body" to issue.body, "labels" to issue.labels)),
    )

    /** The newest issues in any state, with their bodies: where the New task form finds repo suggestions. */
    fun recentIssues(): HttpRequest = get(issuesUrl("state" to "all"))

    private fun get(url: String) = HttpRequest("GET", url, headers())

    private fun issuesUrl(vararg params: Pair<String, String>): String {
        val query = (params.toList() + ("per_page" to PER_PAGE.toString()))
            .joinToString("&") { (k, v) -> "$k=${encode(v)}" }
        return "$API/repos/${repo.fullName}/issues?$query"
    }

    private fun headers() = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28",
        "User-Agent" to "taskboard-widget",
    )

    companion object {
        const val API = "https://api.github.com"
        const val PER_PAGE = 100

        fun isApiUrl(url: String) = url.startsWith("$API/")
    }
}
