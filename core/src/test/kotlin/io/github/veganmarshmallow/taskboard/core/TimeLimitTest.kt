package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.work.TimeLimit
import io.github.veganmarshmallow.taskboard.core.work.Timed
import io.github.veganmarshmallow.taskboard.core.work.toOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TimeLimitTest {
    private val limit = TimeLimit()

    @Test fun returnsTheValue() {
        assertEquals(Timed.Done(4), limit.run(Duration.ofSeconds(5)) { 2 + 2 })
        assertEquals(Timed.Done(null), limit.run(Duration.ofSeconds(5)) { null })
    }

    @Test fun reportsCrashesIncludingErrors() {
        val e = NoSuchMethodError("URLEncoder.encode")
        assertEquals(Timed.Crashed(e), limit.run(Duration.ofSeconds(5)) { throw e })
        val r = IllegalArgumentException("x")
        assertEquals(Timed.Crashed(r), limit.run(Duration.ofSeconds(5)) { throw r })
    }

    @Test fun timesOutAndInterruptsTheWork() {
        val interrupted = CountDownLatch(1)
        val result = limit.run(Duration.ofMillis(100)) {
            try { Thread.sleep(60_000) } catch (e: InterruptedException) { interrupted.countDown() }
        }
        assertEquals(Timed.TimedOut, result)
        assertTrue(interrupted.await(5, TimeUnit.SECONDS))
    }

    @Test fun zeroOrNegativeLimitTimesOutUnlessAlreadyDone() {
        assertEquals(Timed.TimedOut, limit.run(Duration.ZERO) { Thread.sleep(1_000) })
        assertEquals(Timed.TimedOut, limit.run(Duration.ofSeconds(-5)) { Thread.sleep(1_000) })
    }

    @Test fun anInterruptedCallerGetsATimeoutAndKeepsItsFlag() {
        Thread.currentThread().interrupt()
        try {
            assertEquals(Timed.TimedOut, limit.run(Duration.ofSeconds(5)) { Thread.sleep(1_000) })
            assertTrue(Thread.interrupted()) // also clears the flag for the next test
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun aStarterThatNeverRunsTheWorkStillReturns() {
        assertEquals(Timed.TimedOut, TimeLimit(start = {}).run(Duration.ofMillis(50)) { 1 })
    }

    @Test fun timedOutcomesFoldIntoBoardErrors() {
        assertEquals(Outcome.Success(1), Timed.Done(Outcome.Success(1)).toOutcome())
        assertEquals(Outcome.Failure(BoardError.Network), Timed.Done(Outcome.Failure(BoardError.Network)).toOutcome())
        assertEquals(Outcome.Failure(BoardError.Timeout), Timed.TimedOut.toOutcome<Unit>())
        assertEquals(
            Outcome.Failure(BoardError.Internal("NoSuchMethodError")),
            Timed.Crashed(NoSuchMethodError()).toOutcome<Unit>(),
        )
    }
}
