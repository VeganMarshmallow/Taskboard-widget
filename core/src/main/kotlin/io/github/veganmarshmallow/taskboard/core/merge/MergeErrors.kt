package io.github.veganmarshmallow.taskboard.core.merge

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.error.ErrorClassifier
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import io.github.veganmarshmallow.taskboard.core.model.Repo
import java.time.Instant

/**
 * Maps project-repo responses to [MergeProblem]s. Rate limits go through the board's
 * [ErrorClassifier] first (a rate-limited 403 is not an access problem); 401, 5xx and the rest
 * are classified exactly like board errors.
 */
object MergeErrors {
    private const val MAX_DETAIL = 160

    /** update-branch 422 messages that mean "the branch moved or is already up to date", not a conflict. */
    private val UPDATE_LOOK_AGAIN = listOf("expected", "no new commits")

    /** A read (PR, PR list, repo) in [repo]. Null for 2xx. */
    fun forRead(response: HttpResponse, now: Instant, repo: Repo): MergeProblem? {
        if (response.isSuccessful) return null
        common(response, now)?.let { return it }
        return when (response.status) {
            403, 404 -> MergeProblem.NoRepoAccess(repo)
            else -> MergeProblem.Failed(classify(response, now))
        }
    }

    /**
     * The Actions runs list. The PR in [repo] was already read, so a 403/404 here means the token
     * lacks Actions: Read-only for that repo (or Actions is off there). Null for 2xx.
     */
    fun forActions(response: HttpResponse, now: Instant, repo: Repo): MergeProblem? {
        if (response.isSuccessful) return null
        common(response, now)?.let { return it }
        return when (response.status) {
            403, 404 -> MergeProblem.NoActionsAccess(repo)
            else -> MergeProblem.Failed(classify(response, now))
        }
    }

    /**
     * The merge PUT: 200 with `merged: true` is success (null). 405 is "not mergeable" (conflicts,
     * or GitHub's own rule; "already merged" is told apart by its message), 409 a changed head.
     */
    fun forMerge(response: HttpResponse, now: Instant, pr: PullRequest): MergeProblem? {
        val message = PullJsonParser.message(response.body)?.take(MAX_DETAIL)
        if (response.isSuccessful) return if (PullJsonParser.merged(response.body)) null else MergeProblem.Conflicts(pr, message)
        common(response, now)?.let { return it }
        return when (response.status) {
            405 -> if (message?.contains("already merged", ignoreCase = true) == true) MergeProblem.AlreadyMerged(pr)
            else MergeProblem.Conflicts(pr, message)
            409 -> MergeProblem.HeadChanged(pr)
            403, 404 -> MergeProblem.NoRepoAccess(pr.repo, pr)
            422 -> MergeProblem.Failed(BoardError.Unexpected("HTTP 422" + (message?.let { ": $it" } ?: "")))
            else -> MergeProblem.Failed(classify(response, now))
        }
    }

    /**
     * The update-branch PUT (#57): 202 is success (null). 422 is either a moved head (the
     * `expected_head_sha` no longer matches), a branch already up to date ("no new commits", e.g. the
     * repo's update workflow got there first: look again, like a moved head), or a merge conflict.
     */
    fun forUpdate(response: HttpResponse, now: Instant, pr: PullRequest): MergeProblem? {
        if (response.isSuccessful) return null
        common(response, now)?.let { return it }
        val message = PullJsonParser.message(response.body)?.take(MAX_DETAIL)
        return when (response.status) {
            422 -> if (message != null && UPDATE_LOOK_AGAIN.any { message.contains(it, ignoreCase = true) }) {
                MergeProblem.HeadChanged(pr)
            } else {
                MergeProblem.Conflicts(pr, message)
            }
            403, 404 -> MergeProblem.NoRepoAccess(pr.repo, pr)
            else -> MergeProblem.Failed(classify(response, now))
        }
    }

    /** Rate limits and a rejected token (401) mean the same as on the board. */
    private fun common(response: HttpResponse, now: Instant): MergeProblem? = when (val error = classify(response, now)) {
        is BoardError.RateLimited -> MergeProblem.Failed(error)
        is BoardError.BadToken -> if (error.status == 401) MergeProblem.Failed(error) else null
        else -> null
    }

    private fun classify(response: HttpResponse, now: Instant): BoardError =
        ErrorClassifier.classify(response, now) ?: BoardError.Unexpected("HTTP ${response.status}")
}
