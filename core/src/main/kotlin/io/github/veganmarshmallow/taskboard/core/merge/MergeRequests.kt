package io.github.veganmarshmallow.taskboard.core.merge

import io.github.veganmarshmallow.taskboard.core.github.GitHubRequests
import io.github.veganmarshmallow.taskboard.core.github.GitHubRequests.Companion.API
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.model.Repo

/**
 * Requests for merging a task's PR (task #30), in the project repo rather than the board. Repo
 * names are checked here, because they come from issue text and end up in the URL path.
 */
private val OWNER = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$")
private val NAME = Regex("^[A-Za-z0-9._-]{1,100}$")
private val SHA = Regex("^[0-9a-fA-F]{7,64}$")

internal fun repoPath(repo: Repo): String {
    require(OWNER.matches(repo.owner) && NAME.matches(repo.name) && repo.name != "." && repo.name != "..") {
        "not a GitHub repo name"
    }
    return "$API/repos/${repo.owner}/${repo.name}"
}

internal fun GitHubRequests.repository(repo: Repo): HttpRequest = get(repoPath(repo))

internal fun GitHubRequests.pull(ref: PullRef): HttpRequest = get("${repoPath(ref.repo)}/pulls/${ref.number}")

/** The newest open PRs (one page of 100: Claude's project repos never have more open at once). */
internal fun GitHubRequests.openPulls(repo: Repo): HttpRequest =
    get("${repoPath(repo)}/pulls?state=open&per_page=${GitHubRequests.PER_PAGE}")

internal fun GitHubRequests.checkRuns(repo: Repo, sha: String): HttpRequest {
    require(SHA.matches(sha)) { "not a commit SHA" }
    return get("${repoPath(repo)}/commits/$sha/check-runs?per_page=${GitHubRequests.PER_PAGE}")
}

/** A merge commit, pinned to the head SHA that was checked: if the PR changed since, GitHub answers 409. */
internal fun GitHubRequests.mergePull(pr: PullRequest): HttpRequest {
    require(SHA.matches(pr.headSha)) { "not a commit SHA" }
    return withJson("PUT", "${repoPath(pr.repo)}/pulls/${pr.number}/merge", mapOf("merge_method" to "merge", "sha" to pr.headSha))
}
