package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.merge.MergeStep
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllEnd
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllMachine
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllPhase
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllState
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStop.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/** Merge all's state machine (#57): the happy path, every stop reason, cancel, and waiting. */
class MergeAllMachineTest {
    private val github = FakeMergeAllGitHub()
    private val machine = MergeAllMachine(github)
    private var cancel = false

    private fun start(vararg numbers: Int) = MergeAllState.start("run-1", planned(*numbers), github.now)

    /** Steps until the run ends, letting the clock run through every wait. Fails if it never ends. */
    private fun drive(state: MergeAllState, maxSteps: Int = 500): MergeAllState {
        var s = state
        repeat(maxSteps) {
            if (!s.running) return s
            val step = machine.step(s, cancel)
            step.waitFor?.let(github::advance)
            s = step.state
        }
        throw AssertionError("still running after $maxSteps steps: $s")
    }

    private fun stopOf(state: MergeAllState) = (state.end as MergeAllEnd.Stopped)

    @Test fun mergesEachInOrderUpdatingAndRetargetingOnTheWay() {
        github.add(mergeAllPr(31, sha = sha('1')), mergeAllPr(34, base = "claude/31", sha = sha('4')), mergeAllPr(32, sha = sha('2')))
        val end = drive(start(31, 34, 32))
        assertEquals(MergeAllEnd.Done, end.end)
        assertEquals(listOf(31, 34, 32), end.merged.map { it.ref.number })
        assertEquals(
            listOf("merge #31", "retarget #34 to main", "update #34", "merge #34", "update #32", "merge #32"),
            github.calls,
        )
    }

    @Test fun anUpToDatePrIsNotUpdated() {
        github.add(mergeAllPr(31))
        val end = drive(start(31))
        assertEquals(listOf("merge #31"), github.calls)
        assertEquals(MergeAllEnd.Done, end.end)
    }

    @Test fun waitsForTheNewHeadAfterAnUpdate() {
        github.add(mergeAllPr(31, sha = sha('1')))
        github.behind[31] = 2
        var s = start(31)
        s = machine.step(s, false).state // PREPARE
        val updated = machine.step(s, false)
        assertEquals(MergeAllPhase.WAIT, updated.state.phase)
        assertEquals(sha('1'), updated.state.oldHead)
        assertEquals(MergeAllMachine.POLL, updated.waitFor)
        // GitHub hasn't moved the head yet: keep waiting.
        github.prs[31] = github.prs.getValue(31).copy(headSha = sha('1'))
        val same = machine.step(updated.state, false)
        assertEquals(MergeAllPhase.WAIT, same.state.phase)
        assertEquals(MergeAllMachine.POLL, same.waitFor)
        github.prs[31] = github.prs.getValue(31).copy(headSha = sha('9'))
        val moved = machine.step(same.state, false)
        assertEquals(MergeAllPhase.MERGE, moved.state.phase)
        assertEquals("the merge is pinned to the new head", sha('9'), moved.state.checkedHead)
    }

    @Test fun waitsForRunningChecksThenMerges() {
        github.add(mergeAllPr(31))
        var polls = 0
        github.checkResult = { pr -> if (++polls < 4) MergeStep.Stop(MergeProblem.ChecksRunning(pr, listOf("build"))) else MergeStep.Ok(pr) }
        val end = drive(start(31))
        assertEquals(MergeAllEnd.Done, end.end)
        assertEquals(4, polls)
    }

    @Test fun noRunsYetIsWaitedForToo() {
        github.add(mergeAllPr(31))
        github.fail("checks", 31, MergeProblem.NoChecks(mergeAllPr(31)), MergeProblem.MergeabilityUnknown(mergeAllPr(31)))
        assertEquals(MergeAllEnd.Done, drive(start(31)).end)
    }

    @Test fun networkTroubleWhileWaitingIsWaitedOut() {
        github.add(mergeAllPr(31))
        github.fail("checks", 31, MergeProblem.Failed(BoardError.Network), MergeProblem.Failed(BoardError.Server(502)))
        github.fail("pull", 31, MergeProblem.Failed(BoardError.Timeout))
        assertEquals(MergeAllEnd.Done, drive(start(31)).end)
    }

    @Test fun networkTroubleOnRetargetOrUpdateIsWaitedOut() {
        github.add(mergeAllPr(31), mergeAllPr(34, base = "claude/31"))
        github.behind[34] = 1
        github.fail("retarget", 34, MergeProblem.Failed(BoardError.Timeout))
        github.fail("update", 34, MergeProblem.Failed(BoardError.Server(502)))
        val end = drive(start(31, 34))
        assertEquals(MergeAllEnd.Done, end.end)
        assertEquals(listOf("merge #31", "retarget #34 to main", "update #34", "merge #34"), github.calls)
    }

    @Test fun aPrUnreadableForTheWholeTimeoutStopsOnTheNetwork() {
        github.add(mergeAllPr(31), mergeAllPr(32))
        github.fail("pull", 32, *Array(500) { MergeProblem.Failed(BoardError.Network) })
        val end = drive(start(31, 32))
        assertEquals(Kind.NETWORK, stopOf(end).stop.kind)
        assertEquals(1, stopOf(end).at)
        assertEquals(listOf(31), end.merged.map { it.ref.number })
    }

    @Test fun anUpdateThatKeepsFailingStopsAfterTheTimeout() {
        github.add(mergeAllPr(31))
        github.behind[31] = 1
        github.fail("update", 31, *Array(500) { MergeProblem.Failed(BoardError.Unreachable) })
        assertEquals(Kind.NETWORK, stopOf(drive(start(31))).stop.kind)
        assertTrue(github.calls.isEmpty())
    }

    // --- every stop reason ---

    @Test fun conflictWhenUpdatingStopsAndKeepsEarlierMerges() {
        github.add(mergeAllPr(31), mergeAllPr(32), mergeAllPr(33))
        github.fail("update", 32, MergeProblem.Conflicts(mergeAllPr(32), "merge conflict between base and head"))
        val end = drive(start(31, 32, 33))
        val stopped = stopOf(end)
        assertEquals(1, stopped.at)
        assertEquals(Kind.CONFLICT, stopped.stop.kind)
        assertEquals(listOf(31), end.merged.map { it.ref.number })
        assertTrue("PR #33 is left untouched", github.calls.none { "#33" in it })
    }

    @Test fun failedChecksStop() {
        github.add(mergeAllPr(31))
        github.checkResult = { MergeStep.Stop(MergeProblem.ChecksFailed(it, listOf("build", "lint"))) }
        val stop = stopOf(drive(start(31))).stop
        assertEquals(Kind.CHECKS_FAILED, stop.kind)
        assertEquals("build, lint", stop.detail)
        assertTrue(github.calls.isEmpty())
    }

    @Test fun checksThatNeverFinishStopAfterTheTimeout() {
        github.add(mergeAllPr(31))
        github.checkResult = { MergeStep.Stop(MergeProblem.ChecksRunning(it, listOf("emulator"))) }
        val started = github.now
        val stop = stopOf(drive(start(31))).stop
        assertEquals(Kind.CHECKS_TIMEOUT, stop.kind)
        assertEquals("emulator", stop.detail)
        assertFalse(Duration.between(started, github.now) < MergeAllMachine.CHECKS_TIMEOUT)
    }

    @Test fun noPassingChecksStopAfterTheTimeout() {
        github.add(mergeAllPr(31))
        github.checkResult = { MergeStep.Stop(MergeProblem.NoChecks(it)) }
        assertEquals(Kind.NO_CHECKS, stopOf(drive(start(31))).stop.kind)
    }

    @Test fun newCommitsWhileWaitingStop() {
        github.add(mergeAllPr(31, sha = sha('1')))
        var polls = 0
        github.checkResult = { pr ->
            if (++polls == 1) {
                github.prs[31] = pr.copy(headSha = sha('7')) // someone pushes while CI runs
                MergeStep.Stop(MergeProblem.ChecksRunning(pr, listOf("build")))
            } else {
                MergeStep.Ok(pr)
            }
        }
        assertEquals(Kind.PR_CHANGED, stopOf(drive(start(31))).stop.kind)
        assertTrue(github.calls.isEmpty())
    }

    @Test fun headChangedOnTheMergeStops() {
        github.add(mergeAllPr(31))
        github.fail("merge", 31, MergeProblem.HeadChanged(mergeAllPr(31)))
        assertEquals(Kind.PR_CHANGED, stopOf(drive(start(31))).stop.kind)
    }

    @Test fun closedOrDraftOrStackedPrsStop() {
        github.add(mergeAllPr(31, open = false), mergeAllPr(32, draft = true), mergeAllPr(33, base = "claude/99"))
        assertEquals(Kind.PR_CLOSED, stopOf(drive(start(31))).stop.kind)
        assertEquals(Kind.DRAFT, stopOf(drive(start(32))).stop.kind)
        val stacked = stopOf(drive(start(33))).stop
        assertEquals(Kind.STACKED, stacked.kind)
        assertEquals("claude/99", stacked.detail)
        assertTrue("nothing is retargeted onto a branch that wasn't merged", github.calls.isEmpty())
    }

    @Test fun tokenProblemsStop() {
        github.add(mergeAllPr(31), mergeAllPr(32), mergeAllPr(33))
        github.fail("pull", 31, MergeProblem.NoRepoAccess(WIDGET))
        github.fail("checks", 32, MergeProblem.NoActionsAccess(WIDGET))
        github.fail("behind", 33, MergeProblem.Failed(BoardError.BadToken(401)))
        assertEquals(Kind.NO_ACCESS, stopOf(drive(start(31))).stop.kind)
        assertEquals(Kind.NO_ACTIONS_ACCESS, stopOf(drive(start(32))).stop.kind)
        assertEquals(Kind.NO_TOKEN, stopOf(drive(start(33))).stop.kind)
    }

    @Test fun aRateLimitStopsAtOnceEvenWhileWaiting() {
        github.add(mergeAllPr(31))
        github.fail("checks", 31, MergeProblem.Failed(BoardError.RateLimited(null)))
        val start = github.now
        assertEquals(Kind.RATE_LIMITED, stopOf(drive(start(31))).stop.kind)
        assertEquals(start, github.now)
    }

    @Test fun aClearMergeRefusalStops() {
        github.add(mergeAllPr(31))
        github.fail("merge", 31, MergeProblem.Conflicts(mergeAllPr(31), "Pull Request is not mergeable"))
        assertEquals(Kind.CONFLICT, stopOf(drive(start(31))).stop.kind)
    }

    @Test fun anUnexpectedErrorStops() {
        github.add(mergeAllPr(31))
        github.fail("behind", 31, MergeProblem.Failed(BoardError.Unexpected("HTTP 418")))
        val stop = stopOf(drive(start(31))).stop
        assertEquals(Kind.ERROR, stop.kind)
        assertEquals("HTTP 418", stop.detail)
    }

    // --- races, retries, and merges by someone else ---

    @Test fun anUpdateRacingTheRepoWorkflowLooksAgain() {
        github.add(mergeAllPr(31))
        github.behind[31] = 1
        github.fail("update", 31, MergeProblem.HeadChanged(mergeAllPr(31)))
        assertEquals(MergeAllEnd.Done, drive(start(31)).end)
        assertEquals(listOf("update #31", "merge #31"), github.calls)
    }

    @Test fun mainMovingWhileWaitingUpdatesAgain() {
        github.add(mergeAllPr(31))
        var polls = 0
        github.checkResult = { pr ->
            if (++polls == 1) { github.behind[31] = 1; MergeStep.Stop(MergeProblem.ChecksRunning(pr, listOf("build"))) } else MergeStep.Ok(pr)
        }
        assertEquals(MergeAllEnd.Done, drive(start(31)).end)
        assertEquals(listOf("update #31", "merge #31"), github.calls)
    }

    @Test fun aMergeWithoutAnAnswerIsCountedOnceAfterLookingAgain() {
        github.add(mergeAllPr(31), mergeAllPr(32))
        github.lostAnswers += 31 // the PUT went through, but the answer was lost
        val end = drive(start(31, 32))
        assertEquals(MergeAllEnd.Done, end.end)
        assertEquals(listOf(31, 32), end.merged.map { it.ref.number })
        assertEquals(listOf("merge #31", "update #32", "merge #32"), github.calls)
    }

    @Test fun aMergeWithoutAnAnswerIsRetriedWhenItDidNotGoThrough() {
        github.add(mergeAllPr(31))
        github.fail("merge", 31, MergeProblem.Failed(BoardError.Network))
        assertEquals(MergeAllEnd.Done, drive(start(31)).end)
        assertEquals(listOf("merge #31"), github.calls)
    }

    @Test fun aPrMergedBySomeoneElseCountsAndTheRunGoesOn() {
        github.add(mergeAllPr(31), mergeAllPr(32))
        github.mergedOnGitHub(31)
        val end = drive(start(31, 32))
        assertEquals(MergeAllEnd.Done, end.end)
        assertEquals(listOf("update #32", "merge #32"), github.calls)
    }

    // --- cancel ---

    @Test fun cancelStopsBeforeTheNextPr() {
        github.add(mergeAllPr(31), mergeAllPr(32))
        var s = start(31, 32)
        while (s.merged.isEmpty()) s = machine.step(s, false).state
        val end = drive(s.also { cancel = true })
        assertEquals(MergeAllEnd.Cancelled, end.end)
        assertEquals(listOf(31), end.merged.map { it.ref.number })
        assertEquals(listOf("merge #31"), github.calls)
    }

    @Test fun cancelWhileWaitingForChecksMergesNothingMore() {
        github.add(mergeAllPr(31))
        github.checkResult = { MergeStep.Stop(MergeProblem.ChecksRunning(it, listOf("build"))) }
        var s = start(31)
        repeat(4) { s = machine.step(s, false).state }
        assertEquals(MergeAllPhase.WAIT, s.phase)
        cancel = true
        assertEquals(MergeAllEnd.Cancelled, drive(s).end)
        assertTrue(github.calls.isEmpty())
    }

    @Test fun cancelRightBeforeTheMergeDoesNotMerge() {
        github.add(mergeAllPr(31))
        val s = start(31).copy(phase = MergeAllPhase.MERGE, checkedHead = sha('a'))
        val end = machine.step(s, cancel = true).state
        assertEquals(MergeAllEnd.Cancelled, end.end)
        assertTrue(github.calls.isEmpty())
    }

    @Test fun anEndedRunTakesNoMoreSteps() {
        github.add(mergeAllPr(31))
        val done = drive(start(31))
        val again = machine.step(done, false)
        assertEquals(done, again.state)
        assertNull(again.waitFor)
        assertEquals(1, github.calls.size)
    }
}
