package io.github.veganmarshmallow.taskboard.core.preview

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.work.guarded

/**
 * One blocking load for the preview: the issue, then (only if that worked) its comments. Results are
 * handed to [onIssue] and [onComments] as they arrive, so the issue shows before comments are back.
 * A comments failure is swallowed: the preview just omits the Claude line. Never throws.
 */
class PreviewLoader(private val api: GitHubApi?) {
    fun load(
        number: Int,
        onIssue: (Outcome<IssueDetails>) -> Unit,
        onComments: (List<IssueComment>) -> Unit,
    ) {
        val preview = api?.let(::PreviewApi)
        if (preview == null) {
            onIssue(Outcome.Failure(BoardError.NoToken))
            return
        }
        val issue = guarded { preview.fetchIssue(number) }
        onIssue(issue)
        if (issue !is Outcome.Success) return
        val comments = guarded { preview.fetchRecentComments(number) }
        if (comments is Outcome.Success) onComments(comments.value)
    }
}
