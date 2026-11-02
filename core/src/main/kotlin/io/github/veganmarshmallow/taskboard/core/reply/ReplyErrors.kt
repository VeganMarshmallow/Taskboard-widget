package io.github.veganmarshmallow.taskboard.core.reply

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.error.ErrorClassifier
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import io.github.veganmarshmallow.taskboard.core.task.IssueResponseParser
import java.time.Instant

/**
 * Classifies the reply's responses like approving does ([ErrorClassifier.classifyApprove]: bad
 * token, rate limits, 5xx, and 301/404/410 for a deleted or moved issue), plus the two answers only
 * a comment gets: 403 on a locked issue, and 422 with GitHub's own explanation.
 */
object ReplyErrors {
    private val LOCKED = Regex("locked", RegexOption.IGNORE_CASE)
    private const val MAX_DETAIL = 160

    fun forComment(response: HttpResponse, now: Instant, issue: Int): BoardError? {
        if (response.isSuccessful) return null
        val message = IssueResponseParser.errorMessage(response.body)?.take(MAX_DETAIL)
        return when {
            response.status == 403 && message != null && LOCKED.containsMatchIn(message) &&
                ErrorClassifier.classify(response, now) !is BoardError.RateLimited ->
                BoardError.Unexpected("issue #$issue is locked, comments are off")
            response.status == 422 -> BoardError.Unexpected("HTTP 422: " + (message ?: "GitHub rejected the reply"))
            else -> ErrorClassifier.classifyApprove(response, now, issue)
        }
    }

    fun forAddLabels(response: HttpResponse, now: Instant, issue: Int): BoardError? =
        ErrorClassifier.classifyApprove(response, now, issue)

    /** 404 on removing a label means the issue no longer has it: that is the goal, not an error. */
    fun forRemoveLabel(response: HttpResponse, now: Instant, issue: Int): BoardError? =
        if (response.status == 404) null else ErrorClassifier.classifyApprove(response, now, issue)
}
