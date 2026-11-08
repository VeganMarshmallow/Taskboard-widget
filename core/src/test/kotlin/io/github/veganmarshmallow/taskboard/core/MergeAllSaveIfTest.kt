package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.merge.MergeStep
import io.github.veganmarshmallow.taskboard.core.mergeall.LockedMergeAllStore
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllControl
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllEnd
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllMachine
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllPhase
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllRunner
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllState
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * A step's result is saved only over its own unfinished run (#81): a Reset (or a new run) that lands
 * after a step finished but before its save is never undone, and a Cancel tapped then still cancels.
 */
class MergeAllSaveIfTest {
    // --- the store's compare-and-set ---

    private val records = MemoryMergeAllRecords()
    private val locked = LockedMergeAllStore(records, Any())
    private val now = Instant.parse("2026-11-08T10:00:00Z")

    private fun run(id: String) = MergeAllState.start(id, planned(31, 32), now)

    @Test fun savesOverTheSameUnfinishedRun() {
        locked.save(run("a"))
        val next = run("a").at(MergeAllPhase.WAIT, now.plusSeconds(30))
        assertTrue(locked.saveIf(next))
        assertEquals(next, locked.load())
    }

    @Test fun aStepThatEndsTheRunIsSaved() {
        locked.save(run("a"))
        val ended = run("a").stopped(MergeAllStop(MergeAllStop.Kind.CONFLICT), now)
        assertTrue(locked.saveIf(ended))
        assertEquals(ended, locked.load())
    }

    @Test fun refusedOnceTheRunWasReset() {
        locked.save(run("a"))
        locked.clear()
        assertFalse(locked.saveIf(run("a").at(MergeAllPhase.WAIT, now)))
        assertNull(locked.load())
        assertNull("nothing written", records.state)
    }

    @Test fun refusedOverAnotherRun() {
        locked.save(run("b"))
        assertFalse(locked.saveIf(run("a").at(MergeAllPhase.WAIT, now)))
        assertEquals(run("b"), locked.load())
    }

    @Test fun refusedOnceTheRunHasEnded() {
        for (ended in listOf(run("a").cancelled(now), run("a").stopped(MergeAllStop(MergeAllStop.Kind.ERROR), now))) {
            locked.save(ended)
            assertFalse("after ${ended.end}", locked.saveIf(run("a").at(MergeAllPhase.WAIT, now)))
            assertEquals(ended, locked.load())
        }
    }

    @Test fun refusedOverUnreadableText() {
        records.state = "not a run"
        assertFalse(locked.saveIf(run("a")))
        assertEquals("not a run", records.state)
    }

    @Test fun leavesTheCancelFlagAlone() {
        locked.save(run("a"))
        locked.requestCancel("a")
        assertTrue(locked.saveIf(run("a").at(MergeAllPhase.WAIT, now)))
        assertTrue(locked.cancelRequested("a"))
    }

    @Test fun aResetDuringTheCheckWaitsForTheSaveAndThenWins() {
        var armed = false
        lateinit var reset: Thread
        lateinit var store: LockedMergeAllStore
        val records = MemoryMergeAllRecords(onRead = {
            if (armed) {
                armed = false
                reset = Thread { store.clear() }.also(Thread::start)
                reset.join(200) // blocked on the lock: the check and its write are one step
            }
        })
        store = LockedMergeAllStore(records, Any())
        store.save(run("a"))
        armed = true
        assertTrue(store.saveIf(run("a").at(MergeAllPhase.WAIT, now)))
        reset.join(5_000)
        assertNull("the Reset, tapped after the check, is kept", store.load())
    }

    // --- the runner and Cancel ---

    private val github = FakeMergeAllGitHub()
    private val text = TextMergeAllStore()
    private val store = BeforeSaveStore(text)
    private val steps = mutableListOf<MergeAllState>()

    private fun process() = MergeAllRunner(MergeAllMachine(github), store, onStep = { steps += it })

    /** A run waiting on checks that keep running, one poll in. */
    private fun waitingRun() {
        github.add(mergeAllPr(31))
        github.checkResult = { pr -> MergeStep.Stop(MergeProblem.ChecksRunning(pr, listOf("build"))) }
        text.save(MergeAllState.start("run", planned(31), github.now))
        assertEquals(MergeAllMachine.POLL, process().run().next)
        assertEquals(MergeAllPhase.WAIT, text.load()!!.phase)
        steps.clear()
        github.advance(MergeAllMachine.POLL)
    }

    /** [action] once, after the next step finished and before its save. */
    private fun beforeTheNextSave(action: () -> Unit) {
        var done = false
        store.beforeSaveIf = { if (!done) { done = true; action() } }
    }

    @Test fun aResetBetweenAStepAndItsSaveIsNotUndone() {
        waitingRun()
        beforeTheNextSave { MergeAllControl(text) { github.now }.reset() }
        assertEquals(MergeAllRunner.Result(null, null), process().run())
        assertNull(text.load())
        assertEquals(1, store.refused)
        assertTrue("nothing redrawn from the dropped step", steps.isEmpty())
    }

    @Test fun aNewRunStartedBetweenAStepAndItsSaveIsLeftAlone() {
        waitingRun()
        val other = MergeAllState.start("other", planned(32), github.now)
        beforeTheNextSave { text.clear(); assertTrue(text.start(other)) }
        val result = process().run()
        assertEquals(other, result.state)
        assertNull(result.next)
        assertEquals(other, text.load())
    }

    @Test fun aCancelBetweenAStepAndItsSaveStillCancelsRightAfter() {
        waitingRun()
        beforeTheNextSave { text.requestCancel("run") }
        val result = process().run()
        assertNull("no poll wait", result.next)
        assertEquals(MergeAllEnd.Cancelled, text.load()!!.end)
        assertEquals(0, store.refused)
    }

    @Test fun cancelWhileIdleAfterAResetSavesNothing() {
        waitingRun()
        beforeTheNextSave { text.clear() }
        assertNull(MergeAllControl(store) { github.now }.cancel(idle = true))
        assertNull("the Reset is kept", text.load())
    }
}
