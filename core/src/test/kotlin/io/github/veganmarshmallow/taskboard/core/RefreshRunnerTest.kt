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
    private val clock = MutableClock()
    private val marks = TextMarkStore()
    private val states = MemoryStateStore()
    private val board = BoardBuilder().build(listOf(issue(2, Labels.TODO)), NOW)
    private val tracker = RefreshTracker(marks, clock)
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

    @Test fun aSaveFailureStillEndsTheRun() {
        states.failSaves = true
        assertTrue(runCatching { runner().run { it.withBoard(board) } }.exceptionOrNull() is IllegalStateException)
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
        clock.advance(Duration.ofSeconds(30))
        val restarted = RefreshRunner(RefreshTracker(marks, clock), states, clock)
        assertNull("too early to call it dead", restarted.recoverIfStale())
        clock.advance(Duration.ofSeconds(31))
        val recovered = restarted.recoverIfStale()!!
        assertEquals(BoardError.Timeout, recovered.error)
        assertEquals(board, recovered.board)
        assertEquals(clock.now, recovered.attemptedAt)
        assertEquals(recovered, states.state)
        assertEquals(Status.IDLE, RefreshTracker(marks, clock).status())
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
