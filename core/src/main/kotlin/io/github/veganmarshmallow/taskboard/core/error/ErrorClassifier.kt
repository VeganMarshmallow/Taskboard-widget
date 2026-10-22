package io.github.veganmarshmallow.taskboard.core.error

import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import java.time.Instant

/** Maps a non-2xx GitHub response to a [BoardError] using its status code and rate-limit headers. */
object ErrorClassifier {
    /** Returns null for 2xx responses. */
    fun classify(response: HttpResponse, now: Instant): BoardError? {
        val status = response.status
        if (response.isSuccessful) return null
        rateLimit(response, now)?.let { return it }
        return when {
            status == 401 || status == 403 -> BoardError.BadToken(status)
            status == 404 -> BoardError.NoAccess
            status == 429 -> BoardError.RateLimited(null)
            status in 500..599 -> BoardError.Server(status)
            else -> BoardError.Unexpected("HTTP $status")
        }
    }

    /**
     * Primary rate limit: 403/429 with `x-ratelimit-remaining: 0` (retry at `x-ratelimit-reset`).
     * Secondary rate limit: 403/429 with `retry-after` seconds.
     */
    private fun rateLimit(response: HttpResponse, now: Instant): BoardError.RateLimited? {
        if (response.status != 403 && response.status != 429) return null
        val retryAfter = response.header("retry-after")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
        val remaining = response.header("x-ratelimit-remaining")?.trim()?.toLongOrNull()
        return when {
            remaining == 0L -> {
                val reset = response.header("x-ratelimit-reset")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                BoardError.RateLimited(reset?.let(Instant::ofEpochSecond) ?: retryAfter?.let(now::plusSeconds))
            }
            retryAfter != null -> BoardError.RateLimited(now.plusSeconds(retryAfter))
            else -> null
        }
    }
}
