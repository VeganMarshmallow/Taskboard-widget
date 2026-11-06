package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.error.Reachability
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetStateCodec
import io.github.veganmarshmallow.taskboard.core.work.BoardJob
import io.github.veganmarshmallow.taskboard.core.work.RefreshPolicy
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger
import io.github.veganmarshmallow.taskboard.core.work.RetryPlanner
import io.github.veganmarshmallow.taskboard.core.work.RetryStreak
import io.github.veganmarshmallow.taskboard.core.work.RetryStreakCodec
import io.github.veganmarshmallow.taskboard.core.work.UptimeInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/**
 * #29 review: the property the whole fix rests on, checked across the units together. A refresh
 * that fails in a way that can fix itself always gets a retry, within 30 minutes, whatever the
 * wording refinement, the stored streak or the clocks say; and nothing else ever does.
 */
class RecoveryInvariantsTest {
    private val transient = listOf(BoardError.Network, BoardError.Unreachable, BoardError.Timeout, BoardError.Server(500), BoardError.Server(503))
    private val permanent = listOf(
        BoardError.NoToken, BoardError.BadToken(401), BoardError.BadToken(403), BoardError.NoAccess, BoardError.RateLimited(null),
        BoardError.RateLimited(NOW), BoardError.IssueGone(1), BoardError.Internal("RuntimeException"),
    )
    private val steady = Duration.ofMinutes(30)

    @Test fun refiningTheWordingNeverChangesWhetherARefreshIsRetried() {
        (transient + permanent).forEach { e ->
            listOf(true, false).forEach { online ->
                assertEquals("$e online=$online", RefreshPolicy.shouldAutoRetry(e), RefreshPolicy.shouldAutoRetry(Reachability.refine(e, online)))
            }
        }
    }

    @Test fun aTransientFailureAlwaysGetsARetryWithinThirtyMinutesWhateverIsStored() {
        val uptime = FakeUptime(boot = 7, elapsedMs = 10_000_000)
        val stored = listOf(
            null, "", "garbage", "0@7@1", "-1@7@1", "١@7@1", "1@7@99999999999", // malformed, unicode digits, future
            RetryStreakCodec.encode(RetryStreak(3, UptimeInstant(7, 9_999_000))), // a live streak
            RetryStreakCodec.encode(RetryStreak(Int.MAX_VALUE, UptimeInstant(7, 9_999_000))), // saturated
            RetryStreakCodec.encode(RetryStreak(2, UptimeInstant(6, 1))), // another boot
            RetryStreakCodec.encode(RetryStreak(2, UptimeInstant(UptimeInstant.UNKNOWN_BOOT, Long.MIN_VALUE))), // ancient
        )
        stored.forEach { text ->
            transient.forEach { e ->
                val store = TextStreakStore().apply { this.text = text }
                val delay = RetryPlanner(store, uptime).afterRefresh(e)
                assertNotNull("$e after '$text'", delay)
                assertTrue("$e after '$text': $delay", !delay!!.isNegative && !delay.isZero && delay <= steady)
            }
        }
    }

    @Test fun aBrokenStoreNeverCostsTheRetry() {
        val store = TextStreakStore().apply {
            failLoads = true
            failSaves = true
        }
        transient.forEach { assertNotNull("$it", RetryPlanner(store, FakeUptime()).afterRefresh(it)) }
        permanent.forEach { assertNull("$it", RetryPlanner(store, FakeUptime()).afterRefresh(it)) }
    }

    @Test fun permanentFailuresAndSuccessNeverRetry() {
        (permanent + null).forEach { assertNull("$it", RetryPlanner(TextStreakStore(), FakeUptime()).afterRefresh(it)) }
    }

    @Test fun everyPlannedRetryBecomesAPersistedNetworkJobThatSurvivesTheJobStore() {
        val uptime = FakeUptime()
        val planner = RetryPlanner(TextStreakStore(), uptime)
        repeat(6) {
            val delay = planner.afterRefresh(BoardError.Unreachable)!!
            uptime.advance(delay)
            val job = BoardJob.Refresh(RefreshTrigger.RETRY, delay)
            val restored = BoardJob.fromExtras(job.toExtras()::get)
            assertEquals("a retry read back after a reboot or an app update", job, restored)
            assertTrue(job.persisted)
            assertTrue("it waits for a network, so it runs when the network returns", job.needsNetwork)
            assertNull("and never runs without one", job.runAnywayAfter)
            assertFalse("it never displaces a tap's expedited refresh", job.urgent)
            assertEquals(delay, job.minLatency)
        }
    }

    @Test fun aStuckStateSavedByAnOlderVersionStillReadsAsRetryable() {
        listOf(BoardError.Network, BoardError.Unreachable, BoardError.Timeout, BoardError.Server(502)).forEach { e ->
            val saved = WidgetStateCodec.encode(WidgetState(null, e, NOW))
            val error = WidgetStateCodec.decode(saved).error
            assertEquals(e, error)
            assertTrue("$e", RefreshPolicy.shouldAutoRetry(error))
        }
    }
}
