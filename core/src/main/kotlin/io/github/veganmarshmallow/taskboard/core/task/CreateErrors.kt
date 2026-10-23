package io.github.veganmarshmallow.taskboard.core.task

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.error.ErrorClassifier
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import java.time.Instant

/**
 * Classifies a failed issue creation. Bad token, no access, rate limits and server errors are
 * the same as for loading the board ([ErrorClassifier]). 410 means issues are turned off in the
 * repo, and 422 means GitHub rejected the content; both carry GitHub's own explanation.
 */
object CreateErrors {
    fun classify(response: HttpResponse, now: Instant): BoardError? {
        if (response.isSuccessful) return null
        val base = ErrorClassifier.classify(response, now)
        return when (response.status) {
            410 -> BoardError.Unexpected("HTTP 410: issues are disabled on the board repo")
            422 -> BoardError.Unexpected(
                "HTTP 422: " + (IssueResponseParser.errorMessage(response.body) ?: "GitHub rejected the task"),
            )
            else -> base
        }
    }

    /**
     * Whether the issue may exist even though the call failed: the request may have reached GitHub
     * before the connection dropped or timed out ([BoardError.Network]), or GitHub failed after
     * accepting it ([BoardError.Server]). The form then warns the user to check the board before
     * trying again, since creates are never retried automatically.
     */
    fun mayAlreadyExist(error: BoardError): Boolean = error is BoardError.Network || error is BoardError.Server
}
