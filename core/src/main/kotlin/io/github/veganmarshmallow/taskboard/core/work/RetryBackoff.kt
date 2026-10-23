package io.github.veganmarshmallow.taskboard.core.work

import java.time.Duration

/**
 * How long to wait before retrying a failed refresh: [steps] for the first failures in a row
 * (about 1, 5 and 15 minutes by default), then [steady] for every later one, until a refresh works.
 */
class RetryBackoff(
    private val steps: List<Duration> = listOf(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15)),
    private val steady: Duration = Duration.ofMinutes(30),
) {
    init {
        require((steps + steady).none { it.isNegative || it.isZero }) { "delays must be positive" }
    }

    /**
     * The delay after the [failures]th failure in a row (1 = the first). Values below 1 are
     * treated as 1, so a corrupt or reset count never means "no wait".
     */
    fun delayAfter(failures: Int): Duration = steps.getOrNull(failures.coerceAtLeast(1) - 1) ?: steady

    companion object {
        val DEFAULT = RetryBackoff()
    }
}
