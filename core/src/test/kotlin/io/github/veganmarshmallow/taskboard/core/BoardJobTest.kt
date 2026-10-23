package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.error.ErrorMessages
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetStateCodec
import io.github.veganmarshmallow.taskboard.core.work.BoardJob
import io.github.veganmarshmallow.taskboard.core.work.RefreshPolicy
import io.github.veganmarshmallow.taskboard.core.work.RefreshTracker
import io.github.veganmarshmallow.taskboard.core.work.RefreshTracker.Status
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class BoardJobTest {
    private fun roundTrip(job: BoardJob) = BoardJob.fromExtras(job.toExtras()::get)

    @Test fun everyJobRoundTripsThroughItsExtras() {
        listOf(
            BoardJob.Refresh(RefreshTrigger.MANUAL), BoardJob.Refresh(RefreshTrigger.AUTOMATIC),
            BoardJob.Refresh(RefreshTrigger.RETRY), BoardJob.Refresh(RefreshTrigger.RETRY, Duration.ofMinutes(30)),
            BoardJob.Approve(1), BoardJob.Approve(17), BoardJob.Approve(BoardJob.MAX_ISSUE), BoardJob.Sweep,
        ).forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test fun malformedExtrasAreIgnored() {
        listOf(
            emptyMap(), mapOf("kind" to "delete"), mapOf("kind" to "refresh"), mapOf("kind" to "refresh", "trigger" to "manual"),
            mapOf("kind" to "approve"), mapOf("kind" to "approve", "issue" to "0"), mapOf("kind" to "approve", "issue" to "-3"),
            mapOf("kind" to "approve", "issue" to "x"), mapOf("kind" to "approve", "issue" to "99999999999"),
            mapOf("kind" to "approve", "issue" to "${BoardJob.MAX_ISSUE + 1L}"), mapOf("kind" to "REFRESH"),
        ).forEach { assertNull("$it", BoardJob.fromExtras(it::get)) }
    }

    @Test fun approveRejectsOutOfRangeIssues() {
        listOf(0, -1, Int.MIN_VALUE, BoardJob.MAX_ISSUE + 1).forEach {
            assertTrue("$it", runCatching { BoardJob.Approve(it) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun jobIdsNeverCollideAndRefreshesShareOne() {
        assertEquals(BoardJob.Refresh(RefreshTrigger.MANUAL).jobId, BoardJob.Refresh(RefreshTrigger.AUTOMATIC).jobId)
        val ids = listOf(BoardJob.Refresh(RefreshTrigger.MANUAL), BoardJob.Sweep, BoardJob.Approve(1), BoardJob.Approve(2), BoardJob.Approve(BoardJob.MAX_ISSUE))
            .map { it.jobId }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(Int.MAX_VALUE, BoardJob.Approve(BoardJob.MAX_ISSUE).jobId)
    }

    @Test fun everyJobHasAConstraintAndUrgencyFollowsTheTrigger() {
        listOf(BoardJob.Refresh(RefreshTrigger.MANUAL), BoardJob.Refresh(RefreshTrigger.AUTOMATIC), BoardJob.Refresh(RefreshTrigger.RETRY), BoardJob.Approve(3), BoardJob.Sweep)
            .forEach { assertTrue("$it", it.needsNetwork || !it.minLatency.isZero) }
        assertTrue(BoardJob.Refresh(RefreshTrigger.MANUAL).urgent)
        assertFalse(BoardJob.Refresh(RefreshTrigger.AUTOMATIC).urgent)
        assertTrue(BoardJob.Approve(3).urgent)
        assertFalse(BoardJob.Sweep.urgent)
        assertFalse("the sweep must run offline", BoardJob.Sweep.needsNetwork)
        assertTrue("the sweep runs after a run has gone stale", BoardJob.Sweep.minLatency > RefreshTracker.STALE_AFTER)
    }

    @Test fun urgentJobsNeverWaitForever() {
        val manual = BoardJob.Refresh(RefreshTrigger.MANUAL)
        val auto = BoardJob.Refresh(RefreshTrigger.AUTOMATIC)
        val approve = BoardJob.Approve(5)
        listOf(manual, approve).forEach { assertEquals("$it", BoardJob.URGENT_DEADLINE, it.runAnywayAfter) }
        assertNull(auto.runAnywayAfter)
        assertNull(BoardJob.Refresh(RefreshTrigger.RETRY, Duration.ofMinutes(5)).runAnywayAfter)
        assertNull(BoardJob.Sweep.runAnywayAfter)
        assertTrue("a tap ends in well under the stale limit", BoardJob.URGENT_DEADLINE < RefreshTracker.STALE_AFTER)
    }

    @Test fun manualRefreshAlwaysSchedulesAutomaticOnlyOverIdleOrDead() {
        Status.entries.forEach { assertTrue(RefreshPolicy.shouldSchedule(RefreshTrigger.MANUAL, it)) }
        assertTrue(RefreshPolicy.shouldSchedule(RefreshTrigger.AUTOMATIC, Status.IDLE))
        assertTrue(RefreshPolicy.shouldSchedule(RefreshTrigger.AUTOMATIC, Status.STALE))
        assertFalse(RefreshPolicy.shouldSchedule(RefreshTrigger.AUTOMATIC, Status.RUNNING))
    }

    @Test fun onlyTransientFailuresAutoRetry() {
        listOf(BoardError.Network, BoardError.Unreachable, BoardError.Timeout, BoardError.Server(500), BoardError.Server(502), BoardError.Server(599))
            .forEach { assertTrue("$it", RefreshPolicy.shouldAutoRetry(it)) }
        listOf(
            null, BoardError.NoToken, BoardError.BadToken(401), BoardError.BadToken(403), BoardError.NoAccess,
            BoardError.RateLimited(null), BoardError.RateLimited(NOW), BoardError.IssueGone(3), BoardError.Unexpected("x"),
        ).forEach { assertFalse("$it", RefreshPolicy.shouldAutoRetry(it)) }
    }

    @Test fun retryJobsCarryTheirDelayAndArePersisted() {
        val retry = BoardJob.Refresh(RefreshTrigger.RETRY, Duration.ofMinutes(5))
        assertEquals(retry, roundTrip(retry))
        assertEquals(Duration.ofMinutes(5), retry.minLatency)
        assertTrue("waits for a network too", retry.needsNetwork)
        assertFalse(retry.urgent)
        assertTrue("survives a reboot", retry.persisted)
        assertEquals(BoardJob.REFRESH_ID, retry.jobId)
        assertTrue(BoardJob.Refresh(RefreshTrigger.AUTOMATIC).persisted)
        assertFalse("a tap runs now or not at all", BoardJob.Refresh(RefreshTrigger.MANUAL).persisted)
        assertFalse(BoardJob.Approve(2).persisted)
        assertFalse(BoardJob.Sweep.persisted)
        assertEquals(Duration.ZERO, BoardJob.Refresh(RefreshTrigger.AUTOMATIC).minLatency)
        assertEquals(BoardJob.Refresh(RefreshTrigger.RETRY, Duration.ofMillis(1)), roundTrip(BoardJob.Refresh(RefreshTrigger.RETRY, Duration.ofMillis(1))))
    }

    @Test fun refreshDelaysAreValidated() {
        assertTrue(runCatching { BoardJob.Refresh(RefreshTrigger.RETRY, Duration.ofSeconds(-1)) }.exceptionOrNull() is IllegalArgumentException)
        listOf("-5", "x", "", "1.5", "99999999999999999999").forEach {
            assertNull(it, BoardJob.fromExtras(mapOf("kind" to "refresh", "trigger" to "RETRY", "delayMs" to it)::get))
        }
        assertEquals(
            "a job without a delay (from an older version) has none",
            BoardJob.Refresh(RefreshTrigger.RETRY), BoardJob.fromExtras(mapOf("kind" to "refresh", "trigger" to "RETRY")::get),
        )
        assertEquals(
            "a refresh without a delay stores no delay extra", setOf("kind", "trigger"), BoardJob.Refresh(RefreshTrigger.AUTOMATIC).toExtras().keys,
        )
    }

    @Test fun timeoutErrorIsStoredAndExplained() {
        val state = WidgetState(null, BoardError.Timeout, NOW)
        assertEquals(state, WidgetStateCodec.decode(WidgetStateCodec.encode(state)))
        val messages = ErrorMessages { "14:05" }
        assertTrue(messages.message(BoardError.Timeout).contains("too long"))
        assertTrue(messages.widgetMessage(BoardError.Timeout).endsWith("tap to retry"))
        assertFalse(messages.opensSetup(BoardError.Timeout))
    }
}
