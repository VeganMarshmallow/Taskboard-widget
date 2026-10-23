package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.BoardLoader
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.HttpClient
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.work.RefreshRunner
import io.github.veganmarshmallow.taskboard.core.work.RefreshRunner.Result
import io.github.veganmarshmallow.taskboard.core.work.RefreshTracker
import io.github.veganmarshmallow.taskboard.core.work.RefreshTracker.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class RefreshRunnerTest {
    private val time = FakeTime()
    private val clock = time.clock
    private val marks = TextMarkStore()
    private val states = MemoryStateStore()
    private val board = BoardBuilder().build(listOf(issue(2, Labels.TODO)), NOW)
    private val tracker = RefreshTracker(marks, time.uptime)
    private fun runner(budget: Duration = Duration.ofSeconds(5)) = RefreshRunner(tracker, states, clock, budget = budget)
    private fun Result.state() = (this as Result.Committed).state

    @Test fun successfulRunSavesTheBoardAndEndsIdle() {
        var startedWhileRunning = false
        val r = runner().run(onStarted = { startedWhileRunning = tracker.isUpdating() }) { it.withBoard(board) }
        assertTrue(startedWhileRunning)
        assertEquals(board, r.state().board)
        assertEquals(r.state(), states.state)
        assertEquals(Status.IDLE, tracker.status())
    }

    @Test fun aHangingLoadEndsInATimeoutWithinTheBudget() {
        states.state = WidgetState(board, null, NOW)
        val started = System.nanoTime()
        val r = runner(Duration.ofMillis(300)).run { Thread.sleep(60_000); error("unreachable") }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMs ms", tookMs < 5_000)
        assertEquals(BoardError.Timeout, r.state().error)
        assertEquals("the last good board stays", board, r.state().board)
        assertEquals(Status.IDLE, tracker.status())
    }

    @Test fun aHangingHttpClientCannotHoldTheRefresh() {
        val blocking = HttpClient { Thread.sleep(60_000); error("unreachable") }
        val api = GitHubApi(blocking, TOKEN, clock)
        val r = runner(Duration.ofMillis(300)).run { BoardLoader(clock).refresh(it, api) }
        assertEquals(BoardError.Timeout, r.state().error)
        assertEquals(Status.IDLE, tracker.status())
    }

    @Test fun crashesIncludingErrorsEndInAnErrorState() {
        listOf<Throwable>(IllegalStateException("x"), NoSuchMethodError("encode"), StackOverflowError(), OutOfMemoryError()).forEach { t ->
            val r = runner().run { throw t }
            assertEquals("$t", BoardError.Unexpected("internal error: ${t.javaClass.simpleName}"), r.state().error)
            assertEquals(Status.IDLE, tracker.status())
        }
    }

    @Test fun aFailedRunDoesNotBlockTheNextOne() {
        runner().run { throw IllegalStateException("boom") }
        val r = runner().run { it.withBoard(board) }
        assertNull(r.state().error)
        assertEquals(board, r.state().board)
    }

    @Test fun aBrokenOnStartedStepDoesNotStopTheRun() {
        val r = runner().run(onStarted = { throw IllegalStateException("launcher gone") }) { it.withBoard(board) }
        assertEquals(board, r.state().board)
    }

    @Test fun aSaveFailureStillEndsTheRunWithAnErrorToShow() {
        states.state = WidgetState(board, null, NOW)
        states.failSaves = true
        val r = runner().run { it.withBoard(board) } // never throws: the widget must get something to render
        assertEquals(BoardError.Unexpected("internal error: IllegalStateException"), r.state().error)
        assertEquals("the board on disk is what the list shows", board, r.state().board)
        assertEquals(Status.IDLE, tracker.status())
    }

    @Test fun anUnreadableStoredStateStillEndsTheRun() {
        states.failLoads = true
        var seen: WidgetState? = null
        val r = runner().run { seen = it; it.withBoard(board) }
        assertEquals(WidgetState.INITIAL, seen)
        assertEquals(board, r.state().board)
        assertEquals(Status.IDLE, tracker.status())
    }

    @Test fun aFailingMarkStoreAtTheEndStillReturnsAState() {
        val flaky = object : io.github.veganmarshmallow.taskboard.core.work.RefreshMarkStore {
            var mark: io.github.veganmarshmallow.taskboard.core.work.RefreshMark? = null
            override fun load() = mark
            override fun save(mark: io.github.veganmarshmallow.taskboard.core.work.RefreshMark?) {
                if (mark == null) throw IllegalStateException("prefs broken")
                this.mark = mark
            }
        }
        val r = RefreshRunner(RefreshTracker(flaky, time.uptime), states, clock).run { it.withBoard(board) }
        assertTrue(r is Result.Committed)
    }

    @Test fun wallClockJumpsDuringALiveRunChangeNothing() {
        listOf(Duration.ofHours(3), Duration.ofHours(-3), Duration.ofDays(-400)).forEach { jump ->
            val inLoad = CountDownLatch(1)
            val release = CountDownLatch(1)
            val pool = Executors.newSingleThreadExecutor()
            val run = pool.submit<Result> {
                runner().run { inLoad.countDown(); release.await(); it.withBoard(board) }
            }
            assertTrue(inLoad.await(5, TimeUnit.SECONDS))
            clock.advance(jump) // NITZ, a VPN, or the user changed the time: the uptime clock does not move
            assertEquals("$jump", Status.RUNNING, tracker.status())
            assertTrue(tracker.isUpdating())
            assertNull("$jump: a sweep now must not call the live run dead", runner().recoverIfStale())
            release.countDown()
            assertEquals("$jump: the live run's board is kept", board, run.get(5, TimeUnit.SECONDS).state().board)
            assertEquals(Status.IDLE, tracker.status())
            pool.shutdown()
        }
    }

    @Test fun aSweepAfterASuccessfulRefreshLeavesTheGoodBoardAlone() {
        val good = runner().run { it.withBoard(board) }.state()
        val savesBefore = states.saves
        time.advance(Duration.ofSeconds(65)) // when the sweep job fires
        assertNull(runner().recoverIfStale())
        assertEquals(good, states.state)
        assertEquals(savesBefore, states.saves)
    }

    @Test fun aRebootMidRefreshRecoversRightAway() {
        states.state = WidgetState(board, null, NOW)
        tracker.begin() // the phone restarts during the fetch
        time.uptime.reboot(upFor = Duration.ofSeconds(20))
        val recovered = RefreshRunner(RefreshTracker(marks, time.uptime), states, clock).recoverIfStale()!!
        assertEquals(BoardError.Timeout, recovered.error)
        assertEquals(board, recovered.board)
    }

    @Test fun recoveryThatCannotSaveStillReturnsTheErrorAndClearsTheMark() {
        states.state = WidgetState(board, null, NOW)
        tracker.begin()
        time.advance(Duration.ofMinutes(2))
        states.failSaves = true
        assertEquals(BoardError.Timeout, runner().recoverIfStale()?.error)
        assertEquals(Status.IDLE, tracker.status())
    }

    @Test fun aManualRefreshOverAStuckOneWinsAndTheLateResultIsDropped() {
        val release = CountDownLatch(1)
        val stuckStarted = CountDownLatch(1)
        val pool = Executors.newCachedThreadPool()
        val stuck: Future<Result> = pool.submit<Result> {
            runner(Duration.ofSeconds(10)).run(onStarted = { stuckStarted.countDown() }) {
                release.await()
                it.withError(BoardError.Network, NOW)
            }
        }
        assertTrue(stuckStarted.await(5, TimeUnit.SECONDS))
        val manual = runner().run { it.withBoard(board) }
        assertEquals(board, manual.state().board)
        release.countDown()
        assertEquals(Result.Superseded, stuck.get(5, TimeUnit.SECONDS))
        assertNull("the newer board is not overwritten", states.state.error)
        assertEquals(Status.IDLE, tracker.status())
        pool.shutdown()
    }

    @Test fun processDeathMidRefreshRecoversToATimeoutError() {
        states.state = WidgetState(board, null, NOW)
        tracker.begin() // the process is killed during the fetch
        time.advance(Duration.ofSeconds(30))
        val restarted = RefreshRunner(RefreshTracker(marks, time.uptime), states, clock)
        assertNull("too early to call it dead", restarted.recoverIfStale())
        time.advance(Duration.ofSeconds(31))
        val recovered = restarted.recoverIfStale()!!
        assertEquals(BoardError.Timeout, recovered.error)
        assertEquals(board, recovered.board)
        assertEquals(clock.now, recovered.attemptedAt)
        assertEquals(recovered, states.state)
        assertEquals(Status.IDLE, RefreshTracker(marks, time.uptime).status())
        assertNull("nothing left to recover", restarted.recoverIfStale())
    }

    @Test fun recoverIfStaleDoesNothingWhenIdle() {
        assertNull(runner().recoverIfStale())
        assertEquals(0, states.saves)
    }

    @Test fun budgetIsBelowTheStaleLimitAndAboveTheBroadcastLimit() {
        assertTrue(RefreshRunner.RUN_BUDGET < RefreshTracker.STALE_AFTER)
        assertTrue("longer than goAsync's ~10 s", RefreshRunner.RUN_BUDGET > Duration.ofSeconds(10))
        assertFalse(RefreshRunner.RUN_BUDGET.isNegative)
    }
}
