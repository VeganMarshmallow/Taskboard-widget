package io.github.veganmarshmallow.taskboard.core.merge

import io.github.veganmarshmallow.taskboard.core.github.GitHubRequests
import io.github.veganmarshmallow.taskboard.core.github.GitHubRequests.Companion.API
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.model.GitHubNames
import io.github.veganmarshmallow.taskboard.core.model.Repo

/**
 * Requests for merging a task's PR (task #30), in the project repo rather than the board. Repo
 * names are checked here, because they come from issue text and end up in the URL path.
 */
private val SHA = GitHubNames.SHA

internal fun repoPath(repo: Repo): String {
    require(GitHubNames.isOwner(repo.owner) && GitHubNames.isName(repo.name)) {
        "not a GitHub repo name"
    }
    return "$API/repos/${repo.owner}/${repo.name}"
}

internal fun GitHubRequests.repository(repo: Repo): HttpRequest = get(repoPath(repo))

internal fun GitHubRequests.pull(ref: PullRef): HttpRequest = get("${repoPath(ref.repo)}/pulls/${ref.number}")

/** The newest open PRs (one page of 100: Claude's project repos never have more open at once). */
internal fun GitHubRequests.openPulls(repo: Repo): HttpRequest =
    get("${repoPath(repo)}/pulls?state=open&per_page=${GitHubRequests.PER_PAGE}")

/** The GitHub Actions runs on [sha] (task #38). Needs only Actions: Read-only on a fine-grained token. */
internal fun GitHubRequests.workflowRuns(repo: Repo, sha: String): HttpRequest {
    require(SHA.matches(sha)) { "not a commit SHA" }
    return get("${repoPath(repo)}/actions/runs?head_sha=$sha&per_page=${GitHubRequests.PER_PAGE}")
}

/**
 * How far [sha] is behind [base] (#45: `behind_by` > 0 means the PR still has to be updated with it).
 * `{base}...{sha}` compares the merge base with the head, so commits on the head don't count.
 */
internal fun GitHubRequests.compare(repo: Repo, base: String, sha: String): HttpRequest {
    require(GitHubNames.isBranch(base)) { "not a branch name" }
    require(SHA.matches(sha)) { "not a commit SHA" }
    return get("${repoPath(repo)}/compare/$base...$sha")
}

/** A merge commit, pinned to the head SHA that was checked: if the PR changed since, GitHub answers 409. */
internal fun GitHubRequests.mergePull(pr: PullRequest): HttpRequest {
    require(SHA.matches(pr.headSha)) { "not a commit SHA" }
    return withJson("PUT", "${repoPath(pr.repo)}/pulls/${pr.number}/merge", mapOf("merge_method" to "merge", "sha" to pr.headSha))
}
