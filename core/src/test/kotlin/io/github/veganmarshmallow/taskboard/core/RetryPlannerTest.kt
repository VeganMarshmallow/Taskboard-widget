package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.work.RetryPlanner
import io.github.veganmarshmallow.taskboard.core.work.RetryStreak
import io.github.veganmarshmallow.taskboard.core.work.RetryStreakCodec
import io.github.veganmarshmallow.taskboard.core.work.RetryStreakStore
import io.github.veganmarshmallow.taskboard.core.work.UptimeInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A streak store backed by text, like SharedPreferences, so a new planner acts like a restarted process. */
class TextStreakStore : RetryStreakStore {
    @Volatile var text: String? = null
    var failLoads = false
    var failSaves = false
    override fun load(): RetryStreak? {
        if (failLoads) throw IllegalStateException("unreadable")
        return RetryStreakCodec.decode(text)
    }
    override fun save(streak: RetryStreak?) {
        if (failSaves) throw IllegalStateException("disk full")
        text = streak?.let(RetryStreakCodec::encode)
    }
}

class RetryPlannerTest {
    private val store = TextStreakStore()
    private val uptime = FakeUptime()
    private val planner = RetryPlanner(store, uptime)
    private fun min(n: Long) = Duration.ofMinutes(n)

    /** Fails [times] in a row with [error], advancing the clock by each returned delay like a real retry would. */
    private fun failRepeatedly(times: Int, error: BoardError = BoardError.Network): List<Duration?> = (1..times).map {
        planner.afterRefresh(error).also { d -> d?.let(uptime::advance) }
    }

    @Test fun backoffGrowsOneFiveFifteenThenThirty() {
        assertEquals(listOf(min(1), min(5), min(15), min(30), min(30), min(30)), failRepeatedly(6))
    }

    @Test fun everyTransientErrorRetriesAndTheyShareOneStreak() {
        assertEquals(min(1), planner.afterRefresh(BoardError.Network))
        assertEquals(min(5), planner.afterRefresh(BoardError.Unreachable))
        assertEquals(min(15), planner.afterRefresh(BoardError.Timeout))
        assertEquals(min(30), planner.afterRefresh(BoardError.Server(503)))
    }

    @Test fun successResetsTheBackoff() {
        failRepeatedly(4)
        assertNull(planner.afterRefresh(null))
        assertNull("the streak is cleared", store.text)
        assertEquals(min(1), planner.afterRefresh(BoardError.Timeout))
    }

    @Test fun authAccessAndOtherPermanentErrorsNeverRetryAndEndTheStreak() {
        listOf(
            BoardError.NoToken, BoardError.BadToken(401), BoardError.BadToken(403), BoardError.NoAccess,
            BoardError.RateLimited(null), BoardError.RateLimited(NOW), BoardError.IssueGone(9), BoardError.Unexpected("HTTP 418"),
        ).forEach {
            failRepeatedly(3)
            assertNull("$it", planner.afterRefresh(it))
            assertNull("$it ends the streak", store.text)
            assertEquals("$it: the next transient failure starts over", min(1), planner.afterRefresh(BoardError.Network))
            planner.reset()
        }
    }

    @Test fun resetMakesTheNextRetryQuickAgain() {
        failRepeatedly(5)
        planner.reset()
        assertNull(store.text)
        assertEquals(min(1), planner.afterRefresh(BoardError.Network))
    }

    @Test fun theStreakSurvivesARestartedProcess() {
        failRepeatedly(2)
        val restarted = RetryPlanner(store, uptime)
        assertEquals(min(15), restarted.afterRefresh(BoardError.Network))
    }

    @Test fun aStreakJustInsideTheFreshWindowContinues() {
        planner.afterRefresh(BoardError.Network)
        uptime.advance(RetryPlanner.FRESH_FOR)
        assertEquals("exactly at the limit still counts", min(5), planner.afterRefresh(BoardError.Network))
    }

    @Test fun aStreakJustPastTheFreshWindowStartsOver() {
        planner.afterRefresh(BoardError.Network)
        planner.afterRefresh(BoardError.Network)
        uptime.advance(RetryPlanner.FRESH_FOR.plusMillis(1))
        assertEquals(min(1), planner.afterRefresh(BoardError.Network))
    }

    @Test fun theSteadyIntervalNeverExpiresTheStreakByItself() {
        failRepeatedly(4)
        repeat(20) { assertEquals(min(30), planner.afterRefresh(BoardError.Network).also { uptime.advance(min(30)) }) }
    }

    @Test fun aRebootStartsTheStreakOver() {
        failRepeatedly(3)
        uptime.reboot()
        assertEquals(min(1), planner.afterRefresh(BoardError.Network))
    }

    @Test fun anUptimeClockThatWentBackwardsStartsTheStreakOver() {
        failRepeatedly(3)
        uptime.elapsedMs = 5_000 // a reboot the boot count missed: elapsedRealtime restarted near zero
        assertEquals(min(1), planner.afterRefresh(BoardError.Network))
    }

    @Test fun unknownBootCountsFallBackToTheElapsedClock() {
        val unknown = FakeUptime(boot = UptimeInstant.UNKNOWN_BOOT)
        val p = RetryPlanner(store, unknown)
        assertEquals(min(1), p.afterRefresh(BoardError.Network))
        unknown.advance(min(1))
        assertEquals(min(5), p.afterRefresh(BoardError.Network))
        // A streak saved under a known boot, read on a device that cannot tell: the age decides.
        store.text = RetryStreakCodec.encode(RetryStreak(2, UptimeInstant(4, unknown.elapsedMs)))
        assertEquals(min(15), p.afterRefresh(BoardError.Network))
    }

    @Test fun wallClockJumpsDoNotMatter() {
        // The planner only reads the monotonic clock; moving the wall clock (here: never touched) cannot reset or extend a streak.
        val time = FakeTime(uptime = uptime)
        planner.afterRefresh(BoardError.Network)
        time.clock.advance(Duration.ofDays(-3))
        assertEquals(min(5), planner.afterRefresh(BoardError.Network))
        time.clock.advance(Duration.ofDays(30))
        assertEquals(min(15), planner.afterRefresh(BoardError.Network))
    }

    @Test fun theCountSaturatesInsteadOfOverflowing() {
        store.text = RetryStreakCodec.encode(RetryStreak(RetryPlanner.MAX_FAILURES, uptime.now()))
        assertEquals(min(30), planner.afterRefresh(BoardError.Network))
        assertEquals(RetryPlanner.MAX_FAILURES, RetryStreakCodec.decode(store.text)!!.failures)
        store.text = RetryStreakCodec.encode(RetryStreak(Int.MAX_VALUE, uptime.now()))
        assertEquals(min(30), planner.afterRefresh(BoardError.Network))
        assertTrue(RetryStreakCodec.decode(store.text)!!.failures > 0)
    }

    @Test fun anUnreadableOrUnwritableStoreNeverThrowsAndOnlyLosesTheStreak() {
        failRepeatedly(3)
        store.failLoads = true
        assertEquals("unreadable: starts over", min(1), planner.afterRefresh(BoardError.Network))
        store.failLoads = false
        store.failSaves = true
        assertEquals(min(5), planner.afterRefresh(BoardError.Network))
        assertNull(planner.afterRefresh(null))
        planner.reset()
    }

    @Test fun malformedStoredStreaksAreIgnored() {
        listOf("", "x", "1@2", "0@7@100", "-3@7@100", "a@7@100", "1@b@100", "1@7@c", "1@7@100@4", "@@").forEach {
            store.text = it
            assertEquals(it, min(1), planner.afterRefresh(BoardError.Network))
        }
    }

    @Test fun codecRoundTrips() {
        listOf(RetryStreak(1, UptimeInstant(0, 0)), RetryStreak(42, UptimeInstant(-1, Long.MAX_VALUE)), RetryStreak(3, UptimeInstant(9, -5)))
            .forEach { assertEquals(it, RetryStreakCodec.decode(RetryStreakCodec.encode(it))) }
        assertNull(RetryStreakCodec.decode(null))
        assertEquals(RetryStreak(2, UptimeInstant(7, 100)), RetryStreakCodec.decode("  2@7@100\n"))
    }

    @Test fun concurrentFailuresAreAllCounted() {
        val threads = 8
        val each = 25
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        repeat(threads) { pool.execute { start.await(); repeat(each) { planner.afterRefresh(BoardError.Network) } } }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(threads * each, RetryStreakCodec.decode(store.text)!!.failures)
    }

    @Test fun invalidFreshWindowsAreRejected() {
        listOf(Duration.ZERO, Duration.ofMillis(-1)).forEach {
            assertTrue(runCatching { RetryPlanner(store, uptime, freshFor = it) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
