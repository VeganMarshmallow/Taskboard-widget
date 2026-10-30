package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.work.RetryBackoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class RetryBackoffTest {
    private val backoff = RetryBackoff.DEFAULT
    private fun min(n: Long) = Duration.ofMinutes(n)

    @Test fun defaultScheduleIsOneFiveFifteenThenEveryThirtyMinutes() {
        assertEquals(min(1), backoff.delayAfter(1))
        assertEquals(min(5), backoff.delayAfter(2))
        assertEquals(min(15), backoff.delayAfter(3))
        assertEquals(min(30), backoff.delayAfter(4))
        assertEquals(min(30), backoff.delayAfter(5))
        assertEquals(min(30), backoff.delayAfter(100))
        assertEquals(min(30), backoff.delayAfter(Int.MAX_VALUE))
    }

    @Test fun nonPositiveCountsAreTreatedAsTheFirstFailure() {
        listOf(0, -1, Int.MIN_VALUE).forEach { assertEquals("$it", min(1), backoff.delayAfter(it)) }
    }

    @Test fun delaysNeverShrinkAsFailuresGrow() {
        (1..50).zipWithNext().forEach { (a, b) -> assertTrue("$a→$b", backoff.delayAfter(b) >= backoff.delayAfter(a)) }
    }

    @Test fun customSchedulesWork() {
        val quick = RetryBackoff(listOf(Duration.ofSeconds(2)), Duration.ofSeconds(4))
        assertEquals(Duration.ofSeconds(2), quick.delayAfter(1))
        assertEquals(Duration.ofSeconds(4), quick.delayAfter(2))
        val steadyOnly = RetryBackoff(emptyList(), Duration.ofSeconds(9))
        assertEquals(Duration.ofSeconds(9), steadyOnly.delayAfter(1))
    }

    @Test fun zeroOrNegativeDelaysAreRejected() {
        listOf(
            { RetryBackoff(listOf(Duration.ZERO)) },
            { RetryBackoff(listOf(Duration.ofSeconds(-1))) },
            { RetryBackoff(steady = Duration.ZERO) },
            { RetryBackoff(listOf(Duration.ofMinutes(1)), Duration.ofMillis(-1)) },
        ).forEach { assertTrue(runCatching(it).exceptionOrNull() is IllegalArgumentException) }
    }
}
