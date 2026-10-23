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

class BoardJobTest {
    private fun roundTrip(job: BoardJob) = BoardJob.fromExtras(job.toExtras()::get)

    @Test fun everyJobRoundTripsThroughItsExtras() {
        listOf(
            BoardJob.Refresh(RefreshTrigger.MANUAL), BoardJob.Refresh(RefreshTrigger.AUTOMATIC),
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
        listOf(BoardJob.Refresh(RefreshTrigger.MANUAL), BoardJob.Refresh(RefreshTrigger.AUTOMATIC), BoardJob.Approve(3), BoardJob.Sweep)
            .forEach { assertTrue("$it", it.needsNetwork || !it.minLatency.isZero) }
        assertTrue(BoardJob.Refresh(RefreshTrigger.MANUAL).urgent)
        assertFalse(BoardJob.Refresh(RefreshTrigger.AUTOMATIC).urgent)
        assertTrue(BoardJob.Approve(3).urgent)
        assertFalse(BoardJob.Sweep.urgent)
        assertFalse("the sweep must run offline", BoardJob.Sweep.needsNetwork)
        assertTrue("the sweep runs after a run has gone stale", BoardJob.Sweep.minLatency > RefreshTracker.STALE_AFTER)
    }

    @Test fun urgentJobsNeitherWaitForValidationNorWaitForever() {
        val manual = BoardJob.Refresh(RefreshTrigger.MANUAL)
        val auto = BoardJob.Refresh(RefreshTrigger.AUTOMATIC)
        val approve = BoardJob.Approve(5)
        listOf(manual, approve).forEach {
            assertFalse("$it: a tap on a VPN/hotspot must not wait for validation", it.needsValidatedNetwork)
            assertEquals("$it", BoardJob.URGENT_DEADLINE, it.runAnywayAfter)
        }
        assertTrue("background refreshes can wait for a validated network", auto.needsValidatedNetwork)
        assertNull(auto.runAnywayAfter)
        assertFalse("no network at all", BoardJob.Sweep.needsValidatedNetwork)
        assertNull(BoardJob.Sweep.runAnywayAfter)
        assertTrue("a tap ends in well under the stale limit", BoardJob.URGENT_DEADLINE < RefreshTracker.STALE_AFTER)
    }

    @Test fun manualRefreshAlwaysSchedulesAutomaticOnlyOverIdleOrDead() {
        Status.entries.forEach { assertTrue(RefreshPolicy.shouldSchedule(RefreshTrigger.MANUAL, it)) }
        assertTrue(RefreshPolicy.shouldSchedule(RefreshTrigger.AUTOMATIC, Status.IDLE))
        assertTrue(RefreshPolicy.shouldSchedule(RefreshTrigger.AUTOMATIC, Status.STALE))
        assertFalse(RefreshPolicy.shouldSchedule(RefreshTrigger.AUTOMATIC, Status.RUNNING))
    }

    @Test fun onlyAutomaticTransientFailuresRetry() {
        fun state(e: BoardError?) = WidgetState(null, e, NOW)
        listOf(BoardError.Network, BoardError.Timeout, BoardError.Server(502)).forEach {
            assertTrue("$it", RefreshPolicy.shouldRetry(RefreshTrigger.AUTOMATIC, state(it)))
            assertFalse("$it", RefreshPolicy.shouldRetry(RefreshTrigger.MANUAL, state(it)))
        }
        listOf(null, BoardError.NoToken, BoardError.BadToken(401), BoardError.NoAccess, BoardError.RateLimited(null), BoardError.Unexpected("x"))
            .forEach { assertFalse("$it", RefreshPolicy.shouldRetry(RefreshTrigger.AUTOMATIC, state(it))) }
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
