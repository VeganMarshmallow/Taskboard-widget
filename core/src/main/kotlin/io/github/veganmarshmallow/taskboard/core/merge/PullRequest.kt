package io.github.veganmarshmallow.taskboard.core.merge

import io.github.veganmarshmallow.taskboard.core.model.Repo

/** A pull request coordinate: repo plus number. */
data class PullRef(val repo: Repo, val number: Int) {
    init {
        require(number > 0) { "PR number must be positive" }
    }

    /** The PR's page. Built here, never taken from a response, so only github.com links are opened. */
    val webUrl: String get() = "https://github.com/${repo.fullName}/pull/$number"

    override fun toString(): String = "${repo.fullName}#$number"
}

/**
 * A pull request as `GET /repos/{o}/{r}/pulls[/{n}]` returns it, reduced to what merging needs.
 *
 * - [mergeable] is null while GitHub is still computing it (and always in list responses).
 * - [defaultBranch] comes from `base.repo.default_branch`; null when GitHub did not send it.
 */
data class PullRequest(
    val ref: PullRef,
    val title: String,
    val isOpen: Boolean,
    val isDraft: Boolean,
    val isMerged: Boolean,
    val mergeable: Boolean?,
    val body: String,
    val baseRef: String,
    val headRef: String,
    val headSha: String,
    val defaultBranch: String?,
) {
    val number: Int get() = ref.number
    val repo: Repo get() = ref.repo

    /** Targets something other than the default branch: it would merge into another PR's branch. */
    val isStacked: Boolean get() = defaultBranch != null && baseRef != defaultBranch
}
