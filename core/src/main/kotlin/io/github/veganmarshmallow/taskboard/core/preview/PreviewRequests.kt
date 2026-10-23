package io.github.veganmarshmallow.taskboard.core.preview

import io.github.veganmarshmallow.taskboard.core.github.GitHubRequests
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest

/** Requests for the task preview, kept apart from the board's own requests. */
internal fun GitHubRequests.issue(number: Int): HttpRequest {
    require(number > 0) { "issue number must be positive" }
    return get("${GitHubRequests.API}/repos/${repo.fullName}/issues/$number")
}

/** The first page of comments, oldest first (GitHub's only order for this endpoint). */
internal fun GitHubRequests.comments(number: Int): HttpRequest {
    require(number > 0) { "issue number must be positive" }
    return get("${GitHubRequests.API}/repos/${repo.fullName}/issues/$number/comments?per_page=${GitHubRequests.PER_PAGE}")
}
