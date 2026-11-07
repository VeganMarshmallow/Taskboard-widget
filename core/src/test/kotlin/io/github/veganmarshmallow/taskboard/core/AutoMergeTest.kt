package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.automerge.AutoMergeGate
import io.github.veganmarshmallow.taskboard.core.automerge.AutoMergeNotices
import io.github.veganmarshmallow.taskboard.core.automerge.AutoMergeOutcome
import io.github.veganmarshmallow.taskboard.core.automerge.AutoMergeOutcome.Skip
import io.github.veganmarshmallow.taskboard.core.automerge.AutoMergePick
import io.github.veganmarshmallow.taskboard.core.automerge.AutoMergeText
import io.github.veganmarshmallow.taskboard.core.automerge.AutoMerger
import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.MergeLane
import io.github.veganmarshmallow.taskboard.core.board.MergeWait
import io.github.veganmarshmallow.taskboard.core.board.withMergeLanes
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.merge.MergeCandidate
import io.github.veganmarshmallow.taskboard.core.merge.MergeDialogs
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.merge.MergeStep
import io.github.veganmarshmallow.taskboard.core.merge.PullRef
import io.github.veganmarshmallow.taskboard.core.merge.Readiness
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStop
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllText
import io.github.veganmarshmallow.taskboard.core.mergeall.PlannedMerge
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Auto merge (#79): which PR it takes, the checks right before merging, and when a refresh polls at all. */
class AutoMergeTest {
    private fun at(minute: Int): Instant = Instant.parse("2026-11-07T10:00:00Z").plusSeconds(minute * 60L)

    private fun ready(issue: Int, number: Int, minute: Int?, base: String = "main", head: String = "claude/$number") =
        MergeCandidate(issue, "Task $issue", number, Readiness.Ready(minute?.let(::at)), mergeAllPr(number, base = base, head = head))

    private fun waiting(issue: Int, number: Int, wait: MergeWait, base: String = "main") =
        MergeCandidate(issue, "Task $issue", number, Readiness.Waiting(wait), mergeAllPr(number, base = base))

    private fun pick(vararg c: MergeCandidate) = AutoMergePick.next(c.toList())?.pr?.number

    // --- which PR ---

    @Test fun takesTheFirstReadyPrInMergeAllOrder() {
        assertEquals(12, pick(ready(3, 13, null), ready(1, 11, 9), ready(2, 12, 5)))
        assertEquals(11, pick(ready(1, 11, null), ready(2, 12, null)))
    }

    @Test fun nothingReadyMeansNoPick() {
        assertNull(pick())
        assertNull(pick(waiting(1, 11, MergeWait.CHECKS_RUNNING), waiting(2, 12, MergeWait.DRAFT), waiting(3, 13, MergeWait.UPDATING)))
    }

    @Test fun skipsConflictsAndFailedChecks() {
        assertEquals(13, pick(waiting(1, 11, MergeWait.CONFLICT), waiting(2, 12, MergeWait.CONFLICT_CLAUDE_FIXES), waiting(4, 14, MergeWait.CHECKS_FAILED), ready(3, 13, 1)))
    }

    @Test fun neverTakesAPrStackedOnAnUnmergedBase() {
        assertNull(pick(waiting(2, 12, MergeWait.STACKED, base = "claude/11"), waiting(1, 11, MergeWait.CHECKS_RUNNING)))
        // Its base goes first; the stacked one only after the base is merged and it is retargeted.
        assertEquals(11, pick(waiting(2, 12, MergeWait.STACKED, base = "claude/11"), ready(1, 11, 3)))
        // A "ready" PR on another branch (a stale check) is still not taken.
        assertNull(pick(ready(2, 12, 1, base = "claude/11")))
    }

    @Test fun onlyClaudeBranches() {
        assertEquals(12, pick(ready(1, 11, 1, head = "feature/x"), ready(2, 12, 2)))
        assertNull(pick(ready(1, 11, 1, head = "owner/claude/11")))
        assertNull(AutoMergePick.next(listOf(MergeCandidate(1, "Task 1", 11, Readiness.Ready(null), pr = null))))
    }

    // --- one poll ---

    private val github = FakeMergeAllGitHub()
    private var listed: MergeStep<List<MergeCandidate>> = MergeStep.Ok(emptyList())
    private val merger = AutoMerger({ listed }, github)
    private fun planned(number: Int, issue: Int) = PlannedMerge(issue, "Task $issue", PullRef(WIDGET, number))

    private fun withReady(vararg c: MergeCandidate) {
        c.forEach { github.add(it.pr!!) }
        listed = MergeStep.Ok(c.toList())
    }

    @Test fun offByDefaultDoesNothing() {
        withReady(ready(1, 11, 1))
        assertEquals(AutoMergeOutcome.Skipped(Skip.DISABLED), merger.poll(enabled = false, mergeAllRunning = false))
        assertTrue(github.calls.isEmpty())
    }

    @Test fun waitsWhileMergeAllRuns() {
        withReady(ready(1, 11, 1))
        assertEquals(AutoMergeOutcome.Skipped(Skip.MERGE_ALL_RUNNING), merger.poll(enabled = true, mergeAllRunning = true))
        assertTrue(github.calls.isEmpty())
    }

    @Test fun mergesOnlyTheNextReadyPr() {
        withReady(ready(1, 11, 5), ready(2, 12, 1))
        assertEquals(AutoMergeOutcome.Merged(planned(12, 2)), merger.poll(enabled = true, mergeAllRunning = false))
        assertEquals(listOf("merge #12"), github.calls)
    }

    @Test fun nothingReadyIsQuiet() {
        withReady(waiting(1, 11, MergeWait.CHECKS_RUNNING))
        assertEquals(AutoMergeOutcome.Skipped(Skip.NOTHING_READY), merger.poll(true, false))
    }

    @Test fun listingFailureIsQuiet() {
        listed = MergeStep.Stop(MergeProblem.Failed(BoardError.Network))
        assertEquals(AutoMergeOutcome.Skipped(Skip.CANT_CHECK), merger.poll(true, false))
        val crashing = AutoMerger({ throw IllegalStateException("bug") }, github)
        assertEquals(AutoMergeOutcome.Skipped(Skip.CANT_CHECK), crashing.poll(true, false))
    }

    @Test fun aPrBehindItsDefaultBranchWaitsForTheUpdate() {
        withReady(ready(1, 11, 1))
        github.behind[11] = 2
        assertEquals(AutoMergeOutcome.Skipped(Skip.NOT_READY_YET), merger.poll(true, false))
        assertTrue(github.calls.isEmpty())
    }

    @Test fun checksRunningOrPartialRunOnlyIsNotReadyYet() {
        withReady(ready(1, 11, 1))
        github.checkResult = { MergeStep.Stop(MergeProblem.ChecksRunning(it, listOf("build"))) }
        assertEquals(AutoMergeOutcome.Skipped(Skip.NOT_READY_YET), merger.poll(true, false))
        github.checkResult = { MergeStep.Stop(MergeProblem.NoChecks(it)) }
        assertEquals(AutoMergeOutcome.Skipped(Skip.NOT_READY_YET), merger.poll(true, false))
        assertTrue(github.calls.isEmpty())
    }

    @Test fun mergedElsewhereMeanwhileIsQuiet() {
        withReady(ready(1, 11, 1))
        github.mergedOnGitHub(11)
        assertEquals(AutoMergeOutcome.Skipped(Skip.NOT_READY_YET), merger.poll(true, false))
        assertTrue(github.calls.isEmpty())
    }

    @Test fun failedChecksOrConflictsAreReportedAsFailures() {
        withReady(ready(1, 11, 1))
        github.checkResult = { MergeStep.Stop(MergeProblem.ChecksFailed(it, listOf("emulator"))) }
        val failed = merger.poll(true, false) as AutoMergeOutcome.Failed
        assertEquals(planned(11, 1), failed.merge)
        assertEquals(MergeAllStop.Kind.CHECKS_FAILED, failed.stop.kind)
        github.checkResult = { MergeStep.Ok(it) }
        github.fail("merge", 11, MergeProblem.Conflicts(github.prs.getValue(11), "conflict"))
        assertEquals(MergeAllStop.Kind.CONFLICT, (merger.poll(true, false) as AutoMergeOutcome.Failed).stop.kind)
    }

    @Test fun aRefusedMergeIsAFailure() {
        withReady(ready(1, 11, 1))
        github.fail("merge", 11, MergeProblem.NoRepoAccess(WIDGET))
        assertEquals(MergeAllStop.Kind.NO_ACCESS, (merger.poll(true, false) as AutoMergeOutcome.Failed).stop.kind)
    }

    @Test fun aLostMergeAnswerCountsAsMergedWhenItWentThrough() {
        withReady(ready(1, 11, 1))
        github.lostAnswers += 11
        assertEquals(AutoMergeOutcome.Merged(planned(11, 1)), merger.poll(true, false))
    }

    @Test fun aNetworkErrorBeforeMergingIsQuiet() {
        withReady(ready(1, 11, 1))
        github.fail("pull", 11, MergeProblem.Failed(BoardError.Timeout))
        assertEquals(AutoMergeOutcome.Skipped(Skip.NOT_READY_YET), merger.poll(true, false))
        github.fail("merge", 11, MergeProblem.Failed(BoardError.Network))
        github.fail("pull", 11, MergeProblem.Failed(BoardError.Network), MergeProblem.Failed(BoardError.Network))
        assertEquals("re-read fails too: a quiet skip", AutoMergeOutcome.Skipped(Skip.NOT_READY_YET), merger.poll(true, false))
    }

    @Test fun headChangedOrDraftOrClosedIsQuiet() {
        listOf<(io.github.veganmarshmallow.taskboard.core.merge.PullRequest) -> MergeProblem>(
            { MergeProblem.HeadChanged(it) }, { MergeProblem.Draft(it) }, { MergeProblem.Closed(it) },
        ).forEach { problem ->
            assertTrue(AutoMerger.clearsByItself(problem(mergeAllPr(11))))
        }
        assertFalse(AutoMerger.clearsByItself(MergeProblem.Failed(BoardError.RateLimited(null))))
    }

    @Test fun theSameFailureHasTheSameKey() {
        val a = AutoMergeOutcome.Failed(planned(11, 1), MergeAllStop(MergeAllStop.Kind.CHECKS_FAILED, "emulator"))
        assertEquals(a.key, a.copy().key)
        assertNotEquals(a.key, a.copy(stop = MergeAllStop(MergeAllStop.Kind.CONFLICT)).key)
        assertNotEquals(a.key, a.copy(merge = planned(12, 2)).key)
    }

    // --- the gate and the words ---

    private fun boardWith(vararg lanes: MergeLane): Board {
        val issues = lanes.indices.map { Issue(30 + it, "Task ${30 + it}", setOf(Labels.IN_REVIEW), true) }
        return BoardBuilder().build(issues, at(0)).withMergeLanes(lanes.withIndex().associate { (i, lane) -> 30 + i to lane })
    }

    @Test fun pollsOnlyWhenOnAndSomethingIsNext() {
        val next = boardWith(MergeLane.NEXT)
        assertTrue(AutoMergeGate.shouldPoll(enabled = true, mergeAllRunning = false, board = next))
        assertFalse(AutoMergeGate.shouldPoll(enabled = false, mergeAllRunning = false, board = next))
        assertFalse(AutoMergeGate.shouldPoll(enabled = true, mergeAllRunning = true, board = next))
        assertFalse(AutoMergeGate.shouldPoll(true, false, boardWith(MergeLane(false, MergeWait.CHECKS_RUNNING, null))))
        assertFalse(AutoMergeGate.shouldPoll(true, false, null))
    }

    @Test fun noticesSayWhatMergedOrWhy() {
        val text = AutoMergeText(MergeAllText(MergeDialogs(io.github.veganmarshmallow.taskboard.core.error.ErrorMessages { it.toString() })))
        assertNull(text.notice(AutoMergeOutcome.Skipped(Skip.NOTHING_READY)))
        assertEquals("Auto merged Taskboard-widget #11" to "#1 Task 1", text.notice(AutoMergeOutcome.Merged(planned(11, 1))))
        val (title, body) = text.notice(AutoMergeOutcome.Failed(planned(11, 1), MergeAllStop(MergeAllStop.Kind.CHECKS_FAILED, "emulator")))!!
        assertEquals("Auto merge stopped at Taskboard-widget #11", title)
        assertEquals("Checks failed (emulator). #1 Task 1", body)
    }

    @Test fun everyMergeIsNotifiedAFailureOnce() {
        val merged = AutoMergeOutcome.Merged(planned(11, 1))
        val failed = AutoMergeOutcome.Failed(planned(12, 2), MergeAllStop(MergeAllStop.Kind.NO_ACCESS))
        assertTrue(AutoMergeNotices.shouldNotify(null, merged))
        assertTrue(AutoMergeNotices.shouldNotify(failed.key, merged))
        assertTrue(AutoMergeNotices.shouldNotify(null, failed))
        assertFalse("the same failure on the next poll", AutoMergeNotices.shouldNotify(failed.key, failed))
        assertTrue("another failure", AutoMergeNotices.shouldNotify("other", failed))
        Skip.entries.forEach { assertFalse("$it", AutoMergeNotices.shouldNotify(null, AutoMergeOutcome.Skipped(it))) }
    }

    @Test fun aFailureIsForgottenAfterAMergeOrWhenNothingIsLeft() {
        val failed = AutoMergeOutcome.Failed(planned(12, 2), MergeAllStop(MergeAllStop.Kind.NO_ACCESS))
        assertEquals(failed.key, AutoMergeNotices.remembered(null, failed))
        assertNull(AutoMergeNotices.remembered(failed.key, AutoMergeOutcome.Merged(planned(11, 1))))
        assertNull(AutoMergeNotices.remembered(failed.key, AutoMergeOutcome.Skipped(Skip.NOTHING_READY)))
        listOf(Skip.CANT_CHECK, Skip.NOT_READY_YET, Skip.DISABLED, Skip.MERGE_ALL_RUNNING).forEach {
            assertEquals("$it", failed.key, AutoMergeNotices.remembered(failed.key, AutoMergeOutcome.Skipped(it)))
        }
    }
}
