package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.error.ErrorMessages
import io.github.veganmarshmallow.taskboard.core.merge.MergeDialogs
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.merge.MergeStep
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllBanner
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllControl
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllEnd
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllHealth
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllMachine
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllPhase
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllRunner
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllState
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStateCodec
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStop
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllText
import io.github.veganmarshmallow.taskboard.core.work.BoardJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/**
 * Merge all after a network outage (#78): offline waits say so and call nothing, a run not polled
 * for 3 polls is stale and resumes without its stalled time counting, Cancel during a wait is
 * immediate, Reset forgets the run, and the job always runs by a deadline.
 */
class MergeAllStallTest {
    private val github = FakeMergeAllGitHub()
    private val store = TextMergeAllStore()
    private var online = true
    private val text = MergeAllText(MergeDialogs(ErrorMessages { it.toString() }))

    private fun process() = MergeAllRunner(MergeAllMachine(github), store, online = { online })

    private fun running(pr: Int = 31) = MergeAllState.start("run", planned(pr), github.now)

    /** Checks on every head keep running until [passing]. */
    private var passing = false

    private fun waitingRun(): MergeAllState {
        github.add(mergeAllPr(31))
        github.checkResult = { pr -> if (passing) MergeStep.Ok(pr) else MergeStep.Stop(MergeProblem.ChecksRunning(pr, listOf("build"))) }
        store.save(running())
        val first = process().run()
        assertEquals(MergeAllMachine.POLL, first.next)
        return store.load()!!.also { assertEquals(MergeAllPhase.WAIT, it.phase) }
    }

    // --- offline during a wait ---

    @Test fun offlineAPollCallsNothingAndSaysWaitingForNetwork() {
        waitingRun()
        online = false
        val calls = github.calls.size
        github.advance(MergeAllMachine.POLL)
        val result = process().run()
        assertEquals(MergeAllMachine.POLL, result.next)
        val saved = store.load()!!
        assertTrue(saved.offline)
        assertEquals("polled: not stale", github.now, saved.updatedAt)
        assertEquals(calls, github.calls.size)
        assertEquals("Merging 1 of 1… waiting for network", text.progress(saved, cancelling = false))
    }

    @Test fun backOnlineTheWaitGoesOnAndTheRunMerges() {
        waitingRun()
        online = false
        repeat(5) { github.advance(MergeAllMachine.POLL); process().run() }
        online = true
        passing = true
        github.advance(MergeAllMachine.POLL)
        val end = process().run()
        assertNull(end.next)
        assertEquals(MergeAllEnd.Done, end.state!!.end)
        assertFalse(end.state!!.offline)
        assertEquals(listOf("merge #31"), github.calls)
    }

    @Test fun aLongOutageStopsTheRunAsNetworkTroubleNotAsChecks() {
        waitingRun()
        online = false
        var next = process().run().next
        while (next != null) { github.advance(next); next = process().run().next }
        val stop = (store.load()!!.end as MergeAllEnd.Stopped).stop
        assertEquals(MergeAllStop.Kind.NETWORK, stop.kind)
    }

    @Test fun offlineAtTheMergeNeverMerges() {
        github.add(mergeAllPr(31))
        store.save(running().copy(phase = MergeAllPhase.MERGE, checkedHead = sha('a')))
        online = false
        assertEquals(MergeAllMachine.POLL, process().run().next)
        assertTrue(github.calls.isEmpty())
        assertEquals(MergeAllPhase.MERGE, store.load()!!.phase)
    }

    // --- process death and stale runs ---

    @Test fun aRunIsStaleAfterThreeMissedPolls() {
        val state = running()
        assertEquals(MergeAllMachine.POLL.multipliedBy(3), MergeAllHealth.STALE_AFTER)
        assertFalse(MergeAllHealth.stale(state, state.updatedAt.plus(MergeAllHealth.STALE_AFTER)))
        assertTrue(MergeAllHealth.stale(state, state.updatedAt.plus(MergeAllHealth.STALE_AFTER).plusSeconds(1)))
        assertFalse("an ended run is never stale", MergeAllHealth.stale(state.cancelled(state.updatedAt), state.updatedAt.plusSeconds(3600)))
        assertFalse(MergeAllHealth.stale(null, state.updatedAt))
    }

    @Test fun aStaleRunSaysResumingWithCancel() {
        val state = running().copy(phase = MergeAllPhase.WAIT)
        val later = state.updatedAt.plus(Duration.ofHours(2))
        val banner = MergeAllBanner.of(state, false, null, true, text, later)
        assertEquals(MergeAllBanner("Merging 1 of 1… resuming", MergeAllBanner.Button.CANCEL), banner)
        assertEquals("Merging 1 of 1… waiting for checks", MergeAllBanner.of(state, false, null, true, text, state.updatedAt)!!.text)
    }

    @Test fun aProcessDeathDuringAWaitResumesAndMergesOnceChecksPassed() {
        waitingRun()
        github.advance(Duration.ofHours(3)) // no job for hours: GitHub's checks finished meanwhile
        passing = true
        assertTrue(MergeAllHealth.stale(store.load(), github.now))
        val end = process().run()
        assertEquals(MergeAllEnd.Done, end.state!!.end)
        assertEquals(listOf("merge #31"), github.calls)
    }

    @Test fun theStalledTimeDoesNotCountTowardTheWaitLimit() {
        waitingRun()
        github.advance(Duration.ofHours(3))
        val result = process().run()
        assertEquals("still waiting, not stopped on a 60-minute timeout", MergeAllMachine.POLL, result.next)
        val saved = store.load()!!
        assertTrue(saved.running)
        assertTrue(Duration.between(saved.waitSince, github.now) < Duration.ofMinutes(5))
    }

    // --- cancel during a wait ---

    @Test fun cancelBetweenPollsEndsTheRunAtOnce() {
        waitingRun()
        val control = MergeAllControl(store) { github.now }
        val ended = control.cancel(idle = true)!!
        assertEquals(MergeAllEnd.Cancelled, ended.end)
        assertEquals(ended, store.load())
        assertTrue(github.calls.isEmpty())
        assertEquals(MergeAllRunner.Result(ended, null), process().run())
    }

    @Test fun cancelWhileOfflineEndsTheRunAtOnce() {
        waitingRun()
        online = false
        process().run()
        assertEquals(MergeAllEnd.Cancelled, MergeAllControl(store) { github.now }.cancel(idle = true)!!.end)
    }

    @Test fun cancelWhileAStepRunsIsHonouredRightAfterIt() {
        waitingRun()
        val control = MergeAllControl(store) { github.now }
        github.checkResult = { pr ->
            assertNull("a step is in flight: no state saved under it", control.cancel(idle = false))
            MergeStep.Stop(MergeProblem.ChecksRunning(pr, listOf("build")))
        }
        github.advance(MergeAllMachine.POLL)
        val result = process().run()
        assertNull("no poll wait after the step", result.next)
        assertEquals(MergeAllEnd.Cancelled, store.load()!!.end)
    }

    @Test fun cancelAtTheMergeWaitsForThatStep() {
        github.add(mergeAllPr(31))
        store.save(running().copy(phase = MergeAllPhase.MERGE, checkedHead = sha('a')))
        val control = MergeAllControl(store) { github.now }
        assertNull(control.cancel(idle = true))
        assertTrue(store.load()!!.running)
        assertEquals(MergeAllEnd.Cancelled, process().run().state!!.end)
        assertTrue("re-read the PR and stopped before merging", github.calls.isEmpty())
    }

    @Test fun cancelAtAMergeWaitingOnNetworkTroubleDoesNotSpin() {
        github.add(mergeAllPr(31))
        store.save(running().copy(phase = MergeAllPhase.MERGE, checkedHead = sha('a')))
        github.fail("pull", 31, MergeProblem.Failed(BoardError.Network))
        store.requestCancel("run")
        assertEquals("waits a poll", MergeAllMachine.POLL, process().run().next)
    }

    // --- reset ---

    @Test fun resetForgetsTheRunAndAStepInFlightDropsItsResult() {
        waitingRun()
        val control = MergeAllControl(store) { github.now }
        github.checkResult = { pr -> control.reset(); MergeStep.Stop(MergeProblem.ChecksRunning(pr, listOf("build"))) }
        github.advance(MergeAllMachine.POLL)
        assertEquals(MergeAllRunner.Result(null, null), process().run())
        assertNull(store.load())
    }

    @Test fun onceCancelIsTappedTheLineOffersReset() {
        val state = running().copy(phase = MergeAllPhase.MERGE)
        assertEquals(MergeAllBanner.Button.RESET, MergeAllBanner.of(state, true, null, true, text, state.updatedAt)!!.button)
    }

    // --- the codec and the job ---

    @Test fun aRunSavedByAnOlderBuildStillReadsBack() {
        val old = """{"v":1,"run":"run-1","plan":[{"issue":131,"title":"Task 131","owner":"VeganMarshmallow","repo":"Taskboard-widget","pr":31}],""" +
            """"index":0,"phase":"WAIT","merged":[],"oldHead":null,"checkedHead":"${sha('a')}","waitSince":"2026-11-03T10:00:00Z",""" +
            """"end":null,"updatedAt":"2026-11-03T10:00:30Z"}"""
        val state = MergeAllStateCodec.decode(old)!!
        assertFalse(state.offline)
        assertEquals(MergeAllPhase.WAIT, state.phase)
        val offline = state.copy(offline = true)
        assertEquals(offline, MergeAllStateCodec.decode(MergeAllStateCodec.encode(offline)))
        assertNull(MergeAllStateCodec.decode(old.replace("\"end\":null", "\"offline\":\"yes\",\"end\":null")))
    }

    @Test fun theJobRunsByADeadlineEvenWithoutANetwork() {
        assertEquals(BoardJob.MergeAll.DEADLINE_SLACK, BoardJob.MergeAll().runAnywayAfter)
        val poll = BoardJob.MergeAll(MergeAllMachine.POLL)
        assertTrue(poll.needsNetwork)
        assertEquals(MergeAllMachine.POLL.plus(BoardJob.MergeAll.DEADLINE_SLACK), poll.runAnywayAfter)
        assertTrue("a missed deadline is stale", poll.runAnywayAfter < MergeAllHealth.STALE_AFTER)
    }

    // --- reviewer's edge cases ---

    @Test fun offlineAtTheStartCallsNothing() {
        github.add(mergeAllPr(31))
        store.save(running())
        online = false
        assertEquals(MergeAllMachine.POLL, process().run().next)
        val saved = store.load()!!
        assertTrue(github.calls.isEmpty())
        assertEquals(MergeAllPhase.PREPARE, saved.phase)
        assertTrue(saved.offline)
        assertEquals("Merging 1 of 1… waiting for network", text.progress(saved, cancelling = false))
    }

    @Test fun cancelThenResetForgetsTheRun() {
        waitingRun()
        val control = MergeAllControl(store) { github.now }
        assertNull("a step is running: cancel waits", control.cancel(idle = false))
        assertTrue(store.cancelRequested("run"))
        assertEquals(MergeAllBanner.Button.RESET, MergeAllBanner.of(store.load(), true, null, true, text, github.now)!!.button)
        control.reset()
        assertNull(store.load())
        assertEquals(MergeAllRunner.Result(null, null), process().run())
        assertNull(MergeAllBanner.of(store.load(), false, null, false, text, github.now))
    }

    @Test fun aRunReplacedWhileAStepRanIsNotOverwritten() {
        waitingRun()
        github.checkResult = { pr ->
            store.save(MergeAllState.start("other", planned(31), github.now))
            MergeStep.Stop(MergeProblem.ChecksRunning(pr, listOf("build")))
        }
        github.advance(MergeAllMachine.POLL)
        val result = process().run()
        assertNull(result.next)
        assertEquals("other", store.load()!!.runId)
        assertEquals(MergeAllPhase.PREPARE, store.load()!!.phase)
    }

    @Test fun aClockGoingBackwardsIsNeitherStaleNorATimeout() {
        val state = waitingRun()
        val earlier = state.updatedAt.minus(Duration.ofHours(2))
        assertFalse(MergeAllHealth.stale(state, earlier))
        assertEquals(state, MergeAllHealth.resumed(state, earlier))
        github.advance(Duration.ofHours(-2))
        assertEquals(MergeAllMachine.POLL, process().run().next)
        assertTrue(store.load()!!.running)
    }

    @Test fun aRunResumedTwiceInARowShiftsItsWaitOnce() {
        waitingRun()
        github.advance(Duration.ofHours(3))
        process().run()
        val once = store.load()!!.waitSince
        github.advance(MergeAllMachine.POLL)
        process().run()
        assertEquals("the second job finds a fresh run", once, store.load()!!.waitSince)
    }

    @Test fun aJobRunningLateButByItsDeadlineIsNotStale() {
        val waiting = waitingRun()
        github.advance(BoardJob.MergeAll(MergeAllMachine.POLL).runAnywayAfter)
        assertFalse(MergeAllHealth.stale(store.load(), github.now))
        process().run()
        assertEquals("nothing shifted", waiting.waitSince, store.load()!!.waitSince)
    }

    @Test fun theWaitLimitStillAppliesAfterAResume() {
        waitingRun()
        github.advance(Duration.ofHours(3))
        val resumedAt = github.now
        var next = process().run().next
        while (next != null) { github.advance(next); next = process().run().next }
        val stop = (store.load()!!.end as MergeAllEnd.Stopped).stop
        assertEquals(MergeAllStop.Kind.CHECKS_TIMEOUT, stop.kind)
        val polled = Duration.between(resumedAt, github.now)
        assertTrue("stopped ~60 min after the resume, was $polled", polled >= Duration.ofMinutes(59) && polled <= Duration.ofMinutes(61))
    }

    @Test fun cancelWithNoRunOrAnEndedRunDoesNothing() {
        val control = MergeAllControl(store) { github.now }
        assertNull(control.cancel(idle = true))
        val ended = running().cancelled(github.now)
        store.save(ended)
        assertNull(control.cancel(idle = true))
        assertFalse(store.cancelRequested("run"))
        assertEquals(ended, store.load())
    }

    @Test fun cancelBetweenStepsEndsAtOnceInEveryPhaseButMerge() {
        for (phase in MergeAllPhase.values()) {
            store.save(running().copy(phase = phase))
            val ended = MergeAllControl(store) { github.now }.cancel(idle = true)
            assertEquals(phase.name, if (phase == MergeAllPhase.MERGE) null else MergeAllEnd.Cancelled, ended?.end)
            store.clear()
        }
    }

    @Test fun aStallBeforeAnyWaitLeavesNoWaitStart() {
        val state = running()
        assertNull(MergeAllHealth.resumed(state, state.updatedAt.plus(Duration.ofHours(1))).waitSince)
    }

    @Test fun aNullOfflineFieldReadsAsOnline() {
        val json = MergeAllStateCodec.encode(running().copy(offline = true)).replace("\"offline\":true", "\"offline\":null")
        assertTrue(json.contains("\"offline\":null"))
        assertFalse(MergeAllStateCodec.decode(json)!!.offline)
    }
}
