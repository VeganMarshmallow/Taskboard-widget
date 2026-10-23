package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.work.RefreshMark
import io.github.veganmarshmallow.taskboard.core.work.RefreshMarkCodec
import io.github.veganmarshmallow.taskboard.core.work.RefreshTracker
import io.github.veganmarshmallow.taskboard.core.work.RefreshTracker.Status
import io.github.veganmarshmallow.taskboard.core.work.UptimeInstant
import io.github.veganmarshmallow.taskboard.core.work.UptimeInstant.Companion.UNKNOWN_BOOT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RefreshTrackerTest {
    private val clock = FakeUptime()
    private val disk = TextMarkStore()
    private fun tracker() = RefreshTracker(disk, clock)
    private fun at(boot: Int, elapsedMs: Long) = UptimeInstant(boot, elapsedMs)

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

    @Test fun aMarkFromTheFutureOfThisBootIsStale() {
        disk.text = RefreshMarkCodec.encode(RefreshMark(1, at(clock.boot, clock.elapsedMs + 1)))
        assertEquals("the uptime clock never goes back within a boot", Status.STALE, tracker().status())
        disk.text = RefreshMarkCodec.encode(RefreshMark(1, at(clock.boot, clock.elapsedMs)))
        assertEquals(Status.RUNNING, tracker().status())
    }

    @Test fun aRebootMidRunIsStaleAtOnceEvenWithinTheLimit() {
        val t = tracker()
        t.begin()
        clock.reboot(upFor = Duration.ofSeconds(5)) // the phone restarted 5 s into the uptime clock
        assertEquals("never \"updating…\" after a reboot", Status.STALE, t.status())
        assertTrue(t.clearIfStale {})
        assertEquals(Status.IDLE, t.status())
    }

    @Test fun aRebootIsCaughtByTheUptimeClockWhenTheBootCountIsUnknown() {
        disk.text = RefreshMarkCodec.encode(RefreshMark(1, at(UNKNOWN_BOOT, 90_000)))
        clock.boot = UNKNOWN_BOOT
        clock.elapsedMs = 40_000 // lower than at the mark: the clock restarted
        assertEquals(Status.STALE, tracker().status())
        clock.elapsedMs = 100_000 // same boot as far as we can tell, 10 s in
        assertEquals(Status.RUNNING, tracker().status())
        clock.boot = 3 // the boot count became readable: unknown on one side is not a mismatch
        assertEquals(Status.RUNNING, tracker().status())
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
        listOf("", "garbage", "1@", "@5", "a@b", "1@2", "1@x@3", "1@2@3@4", "1@@3", "1762000000000@1762000000000").forEach {
            disk.text = it
            assertEquals(it, Status.IDLE, tracker().status())
        }
        disk.text = "9223372036854775807@7@9223372036854775807" // the far future of this boot: never trusted
        assertEquals(Status.STALE, tracker().status())
        disk.text = "1@7@-9223372036854775808" // the far past (no overflow into "running")
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
        val mark = RefreshMark(42, at(12, 123_456_789))
        assertEquals(mark, RefreshMarkCodec.decode(RefreshMarkCodec.encode(mark)))
        assertEquals(mark, RefreshMarkCodec.decode("  " + RefreshMarkCodec.encode(mark) + "\n"))
        assertEquals(RefreshMark(-1, at(UNKNOWN_BOOT, 0)), RefreshMarkCodec.decode("-1@-1@0"))
        listOf(null, "", " ", "x", "1", "1@x", "x@1", "1@2", "1@2@3@4", "1.5@2@3", "1@2@99999999999999999999", "1@99999999999@3")
            .forEach { assertNull("$it", RefreshMarkCodec.decode(it)) }
        assertNull("the earlier wall-clock form is dropped, not misread", RefreshMarkCodec.decode("42@1761134400123"))
    }
}
