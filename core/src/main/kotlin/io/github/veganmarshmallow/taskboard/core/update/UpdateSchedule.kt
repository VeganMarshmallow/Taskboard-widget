package io.github.veganmarshmallow.taskboard.core.update

import java.time.Duration
import java.time.Instant

/** The background check (during widget refresh) runs at most once per [interval]. */
class UpdateSchedule(private val interval: Duration = Duration.ofDays(1)) {
    fun isDue(lastCheckedAt: Instant?, now: Instant): Boolean {
        if (lastCheckedAt == null) return true
        // A check "in the future" means the clock moved back; don't wait until it catches up.
        if (lastCheckedAt.isAfter(now)) return true
        return !lastCheckedAt.plus(interval).isAfter(now)
    }
}
