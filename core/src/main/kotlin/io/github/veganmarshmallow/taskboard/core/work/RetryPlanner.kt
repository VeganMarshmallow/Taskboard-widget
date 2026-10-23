package io.github.veganmarshmallow.taskboard.core.work

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.work.UptimeInstant.Companion.UNKNOWN_BOOT
import java.time.Duration

/**
 * Decides when the next automatic retry runs after a refresh ends, and remembers the streak of
 * failures across processes so the backoff keeps growing: 1, 5, 15, then every 30 minutes by
 * default. A success, or an error a retry cannot fix, ends the streak.
 *
 * A streak is forgotten (the next failure counts as the first again) when its last failure is
 * older than [freshFor], comes from another boot, or lies in the future of the monotonic clock
 * (a reboot the boot count missed). So after the phone was off or offline for a long time, the
 * first retries are quick again. Ages use the [Uptime] clock, so wall-clock jumps change nothing.
 *
 * Use one instance per process, so [lock] serializes every update.
 */
class RetryPlanner(
    private val store: RetryStreakStore,
    private val uptime: Uptime,
    private val backoff: RetryBackoff = RetryBackoff.DEFAULT,
    private val freshFor: Duration = FRESH_FOR,
) {
    private val lock = Any()

    init {
        require(!freshFor.isNegative && !freshFor.isZero) { "freshFor must be positive" }
    }

    /**
     * Records how a refresh ended ([error] null: success) and returns how long to wait before
     * retrying, or null for no retry. Never throws: an unreadable or unwritable store only loses
     * the streak, which makes the next retry come sooner, never later.
     */
    fun afterRefresh(error: BoardError?): Duration? = synchronized(lock) {
        if (!RefreshPolicy.shouldAutoRetry(error)) {
            saveQuietly(null)
            return null
        }
        val now = uptime.now()
        val previous = loadQuietly()?.takeIf { continues(it, now) }
        val failures = previous?.failures?.let { if (it >= MAX_FAILURES) MAX_FAILURES else it + 1 } ?: 1
        saveQuietly(RetryStreak(failures, now))
        backoff.delayAfter(failures)
    }

    /** Forgets the streak, e.g. after a tap: the person is waiting, so the next retry is quick again. */
    fun reset() = synchronized(lock) { saveQuietly(null) }

    private fun continues(streak: RetryStreak, now: UptimeInstant): Boolean {
        val bootsKnown = streak.lastFailureAt.boot != UNKNOWN_BOOT && now.boot != UNKNOWN_BOOT
        if (bootsKnown && streak.lastFailureAt.boot != now.boot) return false
        val ageMs = now.elapsedMs - streak.lastFailureAt.elapsedMs
        return ageMs >= 0 && ageMs <= freshFor.toMillis()
    }

    private fun loadQuietly(): RetryStreak? = try {
        store.load()
    } catch (e: RuntimeException) {
        null
    }

    private fun saveQuietly(streak: RetryStreak?) {
        try {
            store.save(streak)
        } catch (e: RuntimeException) {
            // Losing the streak only restarts the backoff.
        }
    }

    companion object {
        /** Twice the steady 30-minute interval: a longer gap means retries were not running (offline, off, dozing). */
        val FRESH_FOR: Duration = Duration.ofMinutes(60)

        /** The count stops growing here, so it never overflows; the delay is long since steady anyway. */
        const val MAX_FAILURES = 10_000
    }
}
