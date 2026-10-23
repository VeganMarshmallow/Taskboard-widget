package io.github.veganmarshmallow.taskboard.core.work

/**
 * Refreshes that failed in a row with an error worth retrying: how many ([failures], at least 1)
 * and when the last one happened ([lastFailureAt], on the monotonic [Uptime] clock, never wall time).
 */
data class RetryStreak(val failures: Int, val lastFailureAt: UptimeInstant)

/** Where the streak lives (SharedPreferences on Android). [save] with null clears it. */
interface RetryStreakStore {
    fun load(): RetryStreak?
    fun save(streak: RetryStreak?)
}

/** Text form of a [RetryStreak]: `"<failures>@<boot>@<elapsedMs>"`. Decoding never throws; anything malformed is null. */
object RetryStreakCodec {
    fun encode(streak: RetryStreak): String =
        "${streak.failures}@${streak.lastFailureAt.boot}@${streak.lastFailureAt.elapsedMs}"

    fun decode(text: String?): RetryStreak? {
        val parts = text?.trim()?.split('@') ?: return null
        if (parts.size != 3) return null
        val failures = parts[0].toIntOrNull()?.takeIf { it >= 1 } ?: return null
        val boot = parts[1].toIntOrNull() ?: return null
        val elapsed = parts[2].toLongOrNull() ?: return null
        return RetryStreak(failures, UptimeInstant(boot, elapsed))
    }
}
