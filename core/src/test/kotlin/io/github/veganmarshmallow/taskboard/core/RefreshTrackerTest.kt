package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.work.RefreshMark
import io.github.veganmarshmallow.taskboard.core.work.RefreshMarkCodec
import io.github.veganmarshmallow.taskboard.core.work.RefreshTracker
import io.github.veganmarshmallow.taskboard.core.work.RefreshTracker.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RefreshTrackerTest {
    private val clock = MutableClock()
    private val disk = TextMarkStore()
    private fun tracker() = RefreshTracker(disk, clock)

    @Test fun idleUntilARunBegins() {
        val t = tracker()
        assertEquals(Status.IDLE, t.status())
        assertFalse(t.isUpdating())
        t.begin()
        assertEquals(Status.RUNNING, t.status())
        assertTrue(t.isUpdating())
    }

    @Test fun commitEndsTheRunAndRunsTheCommit() {
        val t = tracker()
        val mark = t.begin()
        var committed = false
        assertTrue(t.commitIfCurrent(mark.runId) { committed = true })
        assertTrue(committed)
        assertEquals(Status.IDLE, t.status())
        assertNull(disk.text)
    }

    @Test fun runningUpToTheLimitThenStale() {
        val t = tracker()
        t.begin()
        clock.advance(RefreshTracker.STALE_AFTER)
        assertEquals("exactly at the limit is still running", Status.RUNNING, t.status())
        clock.advance(Duration.ofMillis(1))
        assertEquals(Status.STALE, t.status())
        assertFalse("a dead run never shows updating…", t.isUpdating())
    }

    @Test fun aMarkFromTheFutureIsStaleButSmallSkewIsNot() {
        disk.text = RefreshMarkCodec.encode(RefreshMark(1, NOW.plusSeconds(4)))
        assertEquals(Status.RUNNING, tracker().status())
        disk.text = RefreshMarkCodec.encode(RefreshMark(1, NOW.plusSeconds(3600)))
        assertEquals("clock moved back: never stuck", Status.STALE, tracker().status())
    }

    @Test fun processDeathMidRunRecoversOnceStale() {
        tracker().begin() // this "process" dies here: nobody commits
        val restarted = tracker()
        assertEquals("a fresh process within the limit cannot tell yet", Status.RUNNING, restarted.status())
        clock.advance(Duration.ofSeconds(61))
        assertEquals(Status.STALE, restarted.status())
        var recovered = 0
        assertTrue(restarted.clearIfStale { recovered++ })
        assertEquals(1, recovered)
        assertEquals(Status.IDLE, restarted.status())
        assertFalse("only once", restarted.clearIfStale { recovered++ })
        assertEquals(1, recovered)
    }

    @Test fun clearIfStaleLeavesALiveRunAlone() {
        val t = tracker()
        t.begin()
        assertFalse(t.clearIfStale { error("must not run") })
        assertEquals(Status.RUNNING, t.status())
        assertFalse("idle too", RefreshTracker(TextMarkStore(), clock).clearIfStale { error("must not run") })
    }

    @Test fun aReplacedRunCannotCommitOrClearTheNewRun() {
        val t = tracker()
        val old = t.begin()
        val new = t.begin()
        assertNotEquals(old.runId, new.runId)
        assertFalse(t.commitIfCurrent(old.runId) { error("stale result must be dropped") })
        assertEquals(Status.RUNNING, t.status())
        assertTrue(t.commitIfCurrent(new.runId) {})
        assertEquals(Status.IDLE, t.status())
    }

    @Test fun aRunThatWasRecoveredAsDeadCannotCommitLater() {
        val t = tracker()
        val mark = t.begin()
        clock.advance(Duration.ofMinutes(5))
        assertTrue(t.clearIfStale {})
        assertFalse(t.commitIfCurrent(mark.runId) { error("must not run") })
    }

    @Test fun aFailingCommitStillEndsTheRun() {
        val t = tracker()
        val mark = t.begin()
        val thrown = runCatching { t.commitIfCurrent(mark.runId) { throw IllegalStateException("disk full") } }
        assertTrue(thrown.exceptionOrNull() is IllegalStateException)
        assertEquals("never stuck on updating… after a failure", Status.IDLE, t.status())
        val again = t.begin()
        assertTrue(t.commitIfCurrent(again.runId) {})
    }

    @Test fun aFailingRecoveryStillClearsTheMark() {
        val t = tracker()
        t.begin()
        clock.advance(Duration.ofMinutes(2))
        runCatching { t.clearIfStale { throw IllegalStateException("x") } }
        assertEquals(Status.IDLE, t.status())
    }

    @Test fun corruptStoredMarkCountsAsIdle() {
        listOf("", "garbage", "1@", "@5", "a@b", "1@2@3").forEach {
            disk.text = it
            assertEquals(it, Status.IDLE, tracker().status())
        }
        disk.text = "9223372036854775807@9223372036854775807" // the far future: decodes, and is never trusted
        assertEquals(Status.STALE, tracker().status())
        disk.text = "1@-9223372036854775808" // the far past
        assertEquals(Status.STALE, tracker().status())
    }

    @Test fun runIdsDoNotRepeatAcrossProcesses() {
        val first = tracker().begin().runId
        clock.advance(Duration.ofMillis(1))
        val second = tracker().begin().runId // a new process, its own counter
        assertNotEquals(first, second)
    }

    @Test fun staleLimitMustBePositive() {
        listOf(Duration.ZERO, Duration.ofSeconds(-1)).forEach {
            assertTrue(runCatching { RefreshTracker(disk, clock, it) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun concurrentRunsLeaveExactlyOneWinner() {
        val t = tracker()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val marks = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        repeat(100) { pool.execute { start.await(); marks.add(t.begin().runId) } }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(100, marks.toSet().size)
        val wins = AtomicInteger()
        marks.forEach { if (t.commitIfCurrent(it) {}) wins.incrementAndGet() }
        assertEquals(1, wins.get())
        assertEquals(Status.IDLE, t.status())
    }

    @Test fun markCodecRoundTripsAndRejectsJunk() {
        val mark = RefreshMark(42, Instant.parse("2026-10-22T12:00:00.123Z"))
        assertEquals(mark, RefreshMarkCodec.decode(RefreshMarkCodec.encode(mark)))
        assertEquals(mark, RefreshMarkCodec.decode("  " + RefreshMarkCodec.encode(mark) + "\n"))
        assertEquals(RefreshMark(-1, Instant.EPOCH), RefreshMarkCodec.decode("-1@0"))
        listOf(null, "", " ", "x", "1", "1@x", "x@1", "1@2@3", "1.5@2", "1@99999999999999999999").forEach {
            assertNull("$it", RefreshMarkCodec.decode(it))
        }
    }
}
