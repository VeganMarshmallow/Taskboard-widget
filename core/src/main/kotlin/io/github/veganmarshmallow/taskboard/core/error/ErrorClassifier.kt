package io.github.veganmarshmallow.taskboard.core.error

import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant

/**
 * Maps a failed GitHub request to a [BoardError]: a non-2xx response by its status code and
 * rate-limit headers ([classify]), and a request that got no response by its exception and what
 * the device says about its network ([classifyFailure]).
 */
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
     * A request that threw instead of returning a response. Connection-level failures get the
     * honest wording from [Reachability] using [probe], which is asked only here, after a failure.
     * Anything else (e.g. a SecurityException or a platform bug inside the HTTP stack) is
     * [BoardError.Unexpected], naming only the exception type so no token can leak.
     */
    fun classifyFailure(e: Exception, probe: NetworkProbe): BoardError {
        val error = when (e) {
            is SocketTimeoutException -> BoardError.Timeout // connected, or tried to, but GitHub never answered in time
            is IOException -> BoardError.Network
            else -> return BoardError.Unexpected("request failed: ${e.javaClass.simpleName}")
        }
        return Reachability.refine(error, probe.deviceOnline())
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
