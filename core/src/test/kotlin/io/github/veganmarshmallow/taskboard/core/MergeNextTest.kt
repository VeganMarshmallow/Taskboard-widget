package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.MergeLane
import io.github.veganmarshmallow.taskboard.core.board.MergeWait
import io.github.veganmarshmallow.taskboard.core.merge.MergeCandidate
import io.github.veganmarshmallow.taskboard.core.merge.MergeNext
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.merge.MergeReadiness
import io.github.veganmarshmallow.taskboard.core.merge.Readiness
import io.github.veganmarshmallow.taskboard.core.merge.WorkflowRun
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Choosing the one PR to merge next (#45), and the reasons the other rows show. */
class MergeNextTest {
    private fun at(minute: Int): Instant = Instant.parse("2026-10-30T10:00:00Z").plusSeconds(minute * 60L)
    private fun ready(issue: Int, pr: Int?, minute: Int?) = MergeCandidate(issue, "Task $issue", pr, Readiness.Ready(minute?.let(::at)))
    private fun waiting(issue: Int, pr: Int?, wait: MergeWait) = MergeCandidate(issue, "Task $issue", pr, Readiness.Waiting(wait))

    @Test fun noCandidatesNoNext() {
        assertNull(MergeNext.pick(emptyList()))
        assertEquals(emptyMap<Int, MergeLane>(), MergeNext.lanes(emptyList()))
    }

    @Test fun theOneReadyFirstWins() {
        val next = MergeNext.pick(listOf(ready(1, 10, 30), ready(2, 11, 5), ready(3, 12, 20)))
        assertEquals(2, next?.issue)
    }

    @Test fun sameTimeFallsBackToOldestPr() {
        assertEquals(3, MergeNext.pick(listOf(ready(1, 12, 5), ready(3, 9, 5), ready(2, 11, 5)))?.issue)
    }

    @Test fun unknownTimeGoesAfterKnownOnes() {
        assertEquals(2, MergeNext.pick(listOf(ready(1, 1, null), ready(2, 50, 59)))?.issue)
        assertEquals(1, MergeNext.pick(listOf(ready(1, 3, null), ready(2, 4, null)))?.issue)
    }

    @Test fun sameTimeAndPrNumberAcrossReposFallsBackToTaskNumber() {
        assertEquals(4, MergeNext.pick(listOf(ready(7, 12, 5), ready(4, 12, 5)))?.issue)
    }

    @Test fun noneReadyNoNextAndEveryRowSaysWhy() {
        val lanes = MergeNext.lanes(listOf(waiting(1, 10, MergeWait.CHECKS_RUNNING), waiting(2, 11, MergeWait.CONFLICT), waiting(3, null, MergeWait.NO_PR)))
        assertEquals(MergeLane(false, MergeWait.CHECKS_RUNNING, null), lanes[1])
        assertEquals(MergeLane(false, MergeWait.CONFLICT, null), lanes[2])
        assertEquals(MergeLane(false, MergeWait.NO_PR, null), lanes[3])
    }

    @Test fun exactlyOneNextOthersPointAtIt() {
        val lanes = MergeNext.lanes(listOf(ready(1, 10, 30), ready(2, 11, 5), waiting(3, 12, MergeWait.STACKED)))
        assertEquals(1, lanes.values.count { it.isNext })
        assertEquals(MergeLane.NEXT, lanes[2])
        assertEquals(MergeLane(false, MergeWait.QUEUED, "Task 2"), lanes[1])
        assertEquals(MergeLane(false, MergeWait.STACKED, "Task 2"), lanes[3])
    }

    @Test fun aTaskListedTwiceKeepsItsFirstEntry() {
        val lanes = MergeNext.lanes(listOf(waiting(1, 10, MergeWait.DRAFT), ready(1, 10, 1)))
        assertEquals(mapOf(1 to MergeLane(false, MergeWait.DRAFT, null)), lanes)
    }

    @Test fun readySinceIsTheLastSuccessfulRunsFinish() {
        fun run(conclusion: String?, updated: Instant?, created: Instant?) =
            WorkflowRun(1, 1, "CI", SHA_A, 1, 1, created, "completed", conclusion, "push", updated)
        assertEquals(at(9), MergeReadiness.readySince(listOf(run("success", at(9), at(1)), run("success", at(4), at(2)), run("skipped", at(20), at(20)))))
        assertEquals(at(3), MergeReadiness.readySince(listOf(run("success", null, at(3)))))
        assertNull(MergeReadiness.readySince(listOf(run("success", null, null))))
        assertNull(MergeReadiness.readySince(emptyList()))
    }

    @Test fun everyProblemMapsToAShortReason() {
        val p = pr(5)
        assertEquals(MergeWait.DRAFT, MergeReadiness.waitFor(MergeProblem.Draft(p)))
        assertEquals(MergeWait.STACKED, MergeReadiness.waitFor(MergeProblem.Stacked(p, 4)))
        assertEquals(MergeWait.CHECKS_RUNNING, MergeReadiness.waitFor(MergeProblem.ChecksRunning(p, listOf("CI"))))
        assertEquals(MergeWait.CHECKS_FAILED, MergeReadiness.waitFor(MergeProblem.ChecksFailed(p, listOf("CI"))))
        assertEquals(MergeWait.NO_CHECKS, MergeReadiness.waitFor(MergeProblem.NoChecks(p)))
        assertEquals(MergeWait.CONFLICT, MergeReadiness.waitFor(MergeProblem.Conflicts(p)))
        assertEquals(MergeWait.UPDATING, MergeReadiness.waitFor(MergeProblem.MergeabilityUnknown(p)))
        assertEquals(MergeWait.NO_PR, MergeReadiness.waitFor(MergeProblem.NotFound))
        assertEquals(MergeWait.NO_PR, MergeReadiness.waitFor(MergeProblem.Ambiguous(listOf(p, pr(6)))))
        assertEquals(MergeWait.UNKNOWN, MergeReadiness.waitFor(MergeProblem.NoRepoAccess(WIDGET)))
        assertEquals(MergeWait.UNKNOWN, MergeReadiness.waitFor(MergeProblem.Failed(BoardError.Network)))
    }
}
