package io.github.veganmarshmallow.taskboard.core.error

import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import java.time.Instant

/** Maps a non-2xx GitHub response to a [BoardError] using its status code and rate-limit headers. */
object ErrorClassifier {
    private val ISSUE_GONE_STATUSES = setOf(301, 404, 410)
    private val RATE_LIMIT_MESSAGE = Regex("rate limit", RegexOption.IGNORE_CASE)

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
     * Like [classify], for adding a label to issue [issueNumber]. The repository was readable when
     * the board loaded, so 404 (no such issue), 410 (deleted) and 301 (transferred to another repo;
     * redirects are never followed) mean the issue itself is gone.
     */
    fun classifyApprove(response: HttpResponse, now: Instant, issueNumber: Int): BoardError? =
        if (response.status in ISSUE_GONE_STATUSES) BoardError.IssueGone(issueNumber) else classify(response, now)

    /**
     * Primary rate limit: 403/429 with `x-ratelimit-remaining: 0` (retry at `x-ratelimit-reset`).
     * Secondary rate limit: 403/429 with `retry-after` seconds, or (GitHub sometimes sends no
     * header at all) a body whose message mentions a rate limit.
     */
    private fun rateLimit(response: HttpResponse, now: Instant): BoardError.RateLimited? {
        if (response.status != 403 && response.status != 429) return null
        val retryAfter = response.header("retry-after")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
        val remaining = response.header("x-ratelimit-remaining")?.trim()?.toLongOrNull()
        return when {
            remaining == 0L -> {
                // A reset time already in the past (device clock ahead of GitHub's) says nothing useful.
                val reset = response.header("x-ratelimit-reset")?.trim()?.toLongOrNull()
                    ?.takeIf { it > 0 }?.let(Instant::ofEpochSecond)?.takeIf { it.isAfter(now) }
                BoardError.RateLimited(reset ?: retryAfter?.let(now::plusSeconds))
            }
            retryAfter != null -> BoardError.RateLimited(now.plusSeconds(retryAfter))
            RATE_LIMIT_MESSAGE.containsMatchIn(response.body) -> BoardError.RateLimited(null)
            else -> null
        }
    }
}
