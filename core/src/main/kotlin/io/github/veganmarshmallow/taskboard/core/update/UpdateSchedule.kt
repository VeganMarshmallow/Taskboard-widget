package io.github.veganmarshmallow.taskboard.core.update

import java.time.Duration
import java.time.Instant

/**
 * When the next automatic check is due: [interval] after a successful check, but at most
 * [retryAfterFailure] after a failed one, so a check that ran while offline is retried soon instead
 * of a day later. A rate limit waits until its reset time.
 */
class UpdateSchedule(
    private val interval: Duration = Duration.ofDays(1),
    private val retryAfterFailure: Duration = Duration.ofHours(1),
) {
    fun isDue(status: UpdateStatus, now: Instant): Boolean {
        val last = status.checkedAt ?: return true
        // A check "in the future" means the clock moved back; don't wait until it catches up.
        if (last.isAfter(now)) return true
        val error = status.error
        if (error is UpdateError.RateLimited && error.retryAt != null && now.isBefore(error.retryAt)) return false
        val wait = if (error == null) interval else minOf(interval, retryAfterFailure)
        return !last.plus(wait).isAfter(now)
    }
}
