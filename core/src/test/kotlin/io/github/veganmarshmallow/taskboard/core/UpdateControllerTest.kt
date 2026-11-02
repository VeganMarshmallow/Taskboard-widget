package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.update.AvailableUpdate
import io.github.veganmarshmallow.taskboard.core.update.UpdateController
import io.github.veganmarshmallow.taskboard.core.update.UpdateError
import io.github.veganmarshmallow.taskboard.core.update.UpdateFlow
import io.github.veganmarshmallow.taskboard.core.update.UpdatePresenter.Activity
import io.github.veganmarshmallow.taskboard.core.update.UpdateResult
import io.github.veganmarshmallow.taskboard.core.update.UpdateSchedule
import io.github.veganmarshmallow.taskboard.core.update.UpdateStatus
import io.github.veganmarshmallow.taskboard.core.update.UpdateStatusStore
import io.github.veganmarshmallow.taskboard.core.update.VersionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

/** [UpdateController] with queued fake threads: tests decide when background work and main-thread posts run. */
class UpdateControllerTest {
    private class Queue {
        val pending = ArrayDeque<Runnable>()
        fun add(r: Runnable) { pending += r }
        fun runNext() = pending.removeFirst().run()
        fun runAll() { while (pending.isNotEmpty()) runNext() }
    }

    private class MemoryStore(var status: UpdateStatus = UpdateStatus.NEVER) : UpdateStatusStore {
        var saves = 0
        override fun load() = status
        override fun save(status: UpdateStatus) { this.status = status; saves++ }
    }

    private val background = Queue()
    private val main = Queue()
    private val store = MemoryStore()
    private var hasToken = true
    private var result: UpdateResult<AvailableUpdate>? = UpdateResult.Ok(AvailableUpdate(VersionInfo(42, "0.1.42"), RELEASE_PAGE))
    private var redraws = 0
    private var fetches = 0

    private val controller = UpdateController(
        flow = UpdateFlow(UpdateSchedule(Duration.ofMinutes(1)), UpdateSchedule(Duration.ofDays(1), Duration.ofHours(1))),
        store = store,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
        hasToken = { hasToken },
        fetchLatest = { fetches++; result },
        installedVersionCode = { 41L },
        redrawBadge = { redraws++ },
        background = { background.add(it) },
        mainThread = { main.add(it) },
    )

    /** Runs the started check and delivers its finish. */
    private fun finishCheck() { background.runAll(); main.runAll() }

    // --- Checks and activity ---

    @Test fun checkShowsCheckingRunsOffTheMainThreadThenGoesIdle() {
        var calls = 0
        controller.addListener { calls++ }
        controller.check()
        assertEquals(Activity.Checking, controller.activity)
        assertEquals(1, calls)
        assertEquals(0, fetches) // nothing ran inline
        background.runAll()
        assertEquals(Activity.Checking, controller.activity) // the finish waits for the main thread
        main.runAll()
        assertEquals(Activity.Idle, controller.activity)
        assertEquals(2, calls)
        assertEquals(1, store.saves)
        assertEquals(1, redraws) // the dot appeared
    }

    @Test fun checkWhileCheckingDoesNotStartASecondCheck() {
        controller.check()
        controller.check()
        assertEquals(1, background.pending.size)
    }

    @Test fun noTokenFinishShowsTheFailureAndCachesNothing() {
        result = null
        controller.check()
        finishCheck()
        assertEquals(Activity.Failed(UpdateError.NoToken), controller.activity)
        assertEquals(0, store.saves)
    }

    @Test fun aListenerAddedMidCheckHearsTheFailedFinish() {
        result = UpdateResult.Err(UpdateError.Network)
        var calls = 0
        controller.check()
        controller.addListener { calls++ }
        finishCheck()
        assertEquals(Activity.Idle, controller.activity)
        assertEquals(1, calls)
        assertEquals(0, redraws) // a failed check with nothing cached before doesn't change the dot
    }

    @Test fun openedWithoutTokenDoesNotCheck() {
        hasToken = false
        controller.opened()
        assertEquals(Activity.Idle, controller.activity)
        assertTrue(background.pending.isEmpty())
    }

    @Test fun openedWithTokenAndNoRecentCheckStartsOne() {
        controller.opened()
        assertEquals(Activity.Checking, controller.activity)
        assertEquals(1, background.pending.size)
        finishCheck()
        assertEquals(1, fetches)
        assertEquals(Activity.Idle, controller.activity)
    }

    @Test fun openedDuringACheckKeepsCheckingWithoutASecondCheck() {
        controller.check()
        controller.opened() // e.g. the screen was rotated mid-check
        assertEquals(Activity.Checking, controller.activity)
        assertEquals(1, background.pending.size)
    }

    @Test fun openedRightAfterACheckDoesNotCheckAgainAndClearsAShownFailure() {
        controller.check()
        finishCheck()
        controller.fail(UpdateError.Network)
        controller.opened() // the check just ran (fixed clock), so it isn't due
        assertEquals(Activity.Idle, controller.activity)
        assertTrue(background.pending.isEmpty())
        assertEquals(1, fetches)
    }

    @Test fun failShowsTheErrorAndNotifies() {
        var calls = 0
        controller.addListener { calls++ }
        controller.fail(UpdateError.Network)
        assertEquals(Activity.Failed(UpdateError.Network), controller.activity)
        assertEquals(1, calls)
    }

    @Test fun anInputThatChangesNothingDoesNotNotify() {
        hasToken = false
        var calls = 0
        controller.addListener { calls++ }
        controller.opened() // Idle -> Idle
        assertEquals(0, calls)
    }

    // --- Stale finishes ---

    @Test fun aSupersededCheckFinishingDoesNotEndTheNewerCheck() {
        controller.check()
        controller.fail(UpdateError.Network) // shown during the first check
        controller.check() // second check starts
        var calls = 0
        controller.addListener { calls++ }
        background.runNext(); main.runAll() // the first check finishes
        assertEquals(Activity.Checking, controller.activity)
        assertEquals(1, calls) // still re-renders the status it cached
        finishCheck() // the second one
        assertEquals(Activity.Idle, controller.activity)
        assertEquals(2, calls)
        assertEquals(2, store.saves)
    }

    @Test fun aSupersededNoTokenFinishIsNotShown() {
        controller.check()
        controller.fail(UpdateError.Network)
        controller.check()
        result = null // the token was removed before the first check ran
        background.runNext(); main.runAll()
        assertEquals(Activity.Checking, controller.activity)
    }

    // --- Background checks ---

    @Test fun backgroundCheckRunsOnTheBackgroundExecutorWhenDueAndIsSilent() {
        var calls = 0
        controller.addListener { calls++ }
        controller.checkInBackgroundIfDue()
        assertEquals(0, fetches)
        background.runAll()
        assertEquals(1, fetches)
        assertEquals(1, store.saves)
        assertTrue(main.pending.isEmpty())
        assertEquals(Activity.Idle, controller.activity)
        assertEquals(0, calls)
    }

    @Test fun backgroundCheckSkipsWhenNotDue() {
        store.status = UpdateStatus(NOW.minus(Duration.ofHours(2)), null)
        controller.checkInBackgroundIfDue()
        background.runAll()
        assertEquals(0, fetches)
    }

    // --- Listeners ---

    @Test fun everyListenerIsNotifiedInOrder() {
        val calls = mutableListOf<String>()
        controller.addListener { calls += "a" }
        controller.addListener { calls += "b" }
        controller.check()
        assertEquals(listOf("a", "b"), calls)
    }

    @Test fun theSameListenerAddedTwiceIsNotifiedOnce() {
        var calls = 0
        val listener = { calls++; Unit }
        controller.addListener(listener)
        controller.addListener(listener)
        controller.check()
        assertEquals(1, calls)
    }

    @Test fun aRemovedListenerIsNotNotified() {
        var calls = 0
        val listener = { calls++; Unit }
        controller.addListener(listener)
        controller.removeListener(listener)
        controller.check()
        assertEquals(0, calls)
    }

    @Test fun removingAListenerThatWasNeverAddedIsHarmless() {
        controller.removeListener {}
        controller.check()
        assertEquals(Activity.Checking, controller.activity)
    }

    @Test fun aListenerCanRemoveItselfDuringNotify() {
        var selfCalls = 0
        var otherCalls = 0
        lateinit var self: () -> Unit
        self = { selfCalls++; controller.removeListener(self) }
        controller.addListener(self)
        controller.addListener { otherCalls++ }
        controller.check()
        finishCheck()
        assertEquals(1, selfCalls)
        assertEquals(2, otherCalls)
    }

    @Test fun aListenerRemovedByAnEarlierOneIsNotCalledInThatRound() {
        var laterCalls = 0
        val later = { laterCalls++; Unit }
        controller.addListener { controller.removeListener(later) }
        controller.addListener(later)
        controller.check()
        assertEquals(0, laterCalls)
    }

    @Test fun aListenerAddedDuringNotifyIsCalledFromTheNextChange() {
        var addedCalls = 0
        var added = false
        controller.addListener {
            if (!added) { added = true; controller.addListener { addedCalls++ } }
        }
        controller.check()
        assertEquals(0, addedCalls)
        finishCheck()
        assertEquals(1, addedCalls)
    }

    @Test fun aThrowingListenerDoesNotStopTheOthersAndItsExceptionIsRethrown() {
        val boom = IllegalStateException("boom")
        var otherCalls = 0
        controller.addListener { throw boom }
        controller.addListener { otherCalls++ }
        try {
            controller.check()
            fail("expected the listener's exception")
        } catch (e: IllegalStateException) {
            assertSame(boom, e)
        }
        assertEquals(1, otherCalls)
        assertEquals(Activity.Checking, controller.activity) // the state still moved
        assertEquals(1, background.pending.size) // and the check still started
    }

    @Test fun twoThrowingListenersRethrowTheFirstWithTheSecondSuppressed() {
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        controller.addListener { throw first }
        controller.addListener { throw second }
        try {
            controller.check()
            fail("expected the first listener's exception")
        } catch (e: IllegalStateException) {
            assertSame(first, e)
            assertSame(second, e.suppressed.single())
        }
    }

    @Test fun aCheckFinishingAfterEveryListenerLeftStillCachesAndGoesIdle() {
        var calls = 0
        val listener = { calls++; Unit }
        controller.addListener(listener)
        controller.check()
        controller.removeListener(listener) // the screen closed mid-check
        finishCheck()
        assertEquals(1, calls)
        assertEquals(Activity.Idle, controller.activity)
        assertEquals(1, store.saves)
        assertNull(store.status.error)
    }
}
