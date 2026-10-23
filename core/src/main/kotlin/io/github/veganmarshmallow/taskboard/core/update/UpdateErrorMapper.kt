package io.github.veganmarshmallow.taskboard.core.update

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.error.ErrorClassifier
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import java.time.Instant

/**
 * Maps a non-2xx response from the updater's requests to an [UpdateError]. Rate limits and server
 * errors are recognised by the board's [ErrorClassifier]; what 403 and 404 mean depends on [step].
 */
object UpdateErrorMapper {
    enum class Step {
        /** `GET /repos/{app}/releases/tags/latest`: 404 is ambiguous (no access, or no release). */
        RELEASE,

        /** `GET /repos/{app}`: run after a release 404, to tell "no access" from "no release". */
        REPOSITORY,

        /** `GET /repos/{app}/releases/assets/{id}` on api.github.com. */
        ASSET,

        /** The storage host the asset redirected to. It never sees the token, so 401/403 aren't about it. */
        STORAGE,
    }

    /** Null for 2xx. For [Step.RELEASE] a 404 maps to [UpdateError.NoRelease]; the caller probes the repo first. */
    fun map(response: HttpResponse, step: Step, now: Instant): UpdateError? {
        if (response.isSuccessful) return null
        if (step == Step.STORAGE) return storage(response.status)
        return when (val board = ErrorClassifier.classify(response, now)) {
            null -> null
            is BoardError.RateLimited -> UpdateError.RateLimited(board.retryAt)
            is BoardError.BadToken -> if (board.status == 401) UpdateError.BadToken else UpdateError.NoRepoAccess
            BoardError.NoAccess -> when (step) {
                Step.RELEASE -> UpdateError.NoRelease
                Step.REPOSITORY -> UpdateError.NoRepoAccess
                else -> UpdateError.ReleaseChanged
            }
            is BoardError.Server -> UpdateError.Server(board.status)
            is BoardError.Unexpected -> if (response.status == 410) UpdateError.ReleaseChanged else UpdateError.Unexpected(board.detail)
            else -> UpdateError.Unexpected("HTTP ${response.status}")
        }
    }

    private fun storage(status: Int): UpdateError = when (status) {
        // Signed download links expire after a few minutes; a fresh check gets a new one.
        401, 403, 404, 410 -> UpdateError.ReleaseChanged
        in 500..599 -> UpdateError.Server(status)
        else -> UpdateError.Unexpected("download host returned HTTP $status")
    }
}
