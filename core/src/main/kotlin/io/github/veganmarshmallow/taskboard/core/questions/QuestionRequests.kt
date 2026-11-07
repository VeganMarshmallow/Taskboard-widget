package io.github.veganmarshmallow.taskboard.core.questions

import io.github.veganmarshmallow.taskboard.core.github.GitHubRequests
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.QueryEncoding.encode
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Every issue comment in the board repo updated since [since], newest first: one listing instead
 * of one request per task (task #82).
 */
internal fun GitHubRequests.repoComments(since: Instant): HttpRequest {
    val time = encode(since.truncatedTo(ChronoUnit.SECONDS).toString())
    return get("${GitHubRequests.API}/repos/${repo.fullName}/issues/comments?sort=created&direction=desc&since=$time&per_page=${GitHubRequests.PER_PAGE}")
}
