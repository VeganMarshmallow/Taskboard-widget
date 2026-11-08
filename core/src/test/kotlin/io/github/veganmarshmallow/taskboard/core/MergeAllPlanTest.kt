package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.MergeLane
import io.github.veganmarshmallow.taskboard.core.board.MergeWait
import io.github.veganmarshmallow.taskboard.core.board.withMergeLanes
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.json.JsonWriter
import io.github.veganmarshmallow.taskboard.core.merge.MergeCandidate
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.merge.MergeStep
import io.github.veganmarshmallow.taskboard.core.merge.PullRef
import io.github.veganmarshmallow.taskboard.core.merge.Readiness
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllOffer
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllPlanner
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllPlanning
import io.github.veganmarshmallow.taskboard.core.mergeall.PlannedMerge
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.model.Repo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Merge all's plan and order (#57): merge-next order, stacks bottom-up, and when the widget offers it. */
class MergeAllPlanTest {
    private val other = Repo("VeganMarshmallow", "Other")
    private fun at(minute: Int): Instant = Instant.parse("2026-10-30T10:00:00Z").plusSeconds(minute * 60L)

    private fun ready(issue: Int, number: Int, minute: Int?, repo: Repo = WIDGET, base: String = "main") =
        MergeCandidate(issue, "Task $issue", number, Readiness.Ready(minute?.let(::at)), pr(number, repo = repo, base = base))

    private fun waiting(issue: Int, number: Int?, wait: MergeWait, repo: Repo = WIDGET, base: String = "main") =
        MergeCandidate(issue, "Task $issue", number, Readiness.Waiting(wait), number?.let { pr(it, repo = repo, base = base) })

    private fun order(vararg candidates: MergeCandidate) = MergeAllPlanner.order(candidates.toList()).map { it.pr.number }

    @Test fun readyPrsInMergeNextOrder() {
        // #12 was ready first; #11 and #13 have no time (last), then the oldest PR first.
        assertEquals(listOf(12, 11, 13), order(ready(3, 13, null), ready(1, 11, null), ready(2, 12, 5)))
    }

    @Test fun eachPlannedMergeNamesItsTaskAndPr() {
        assertEquals(listOf(PlannedMerge(1, "Task 1", PullRef(WIDGET, 11))), MergeAllPlanner.order(listOf(ready(1, 11, 1))))
    }

    @Test fun stackedPrsFollowTheirBaseBottomUp() {
        val plan = order(
            waiting(5, 15, MergeWait.STACKED, base = "claude/14"), // on top of #14
            ready(1, 11, 9),
            waiting(4, 14, MergeWait.STACKED, base = "claude/12"), // on #12
            ready(2, 12, 1),
            waiting(6, 16, MergeWait.STACKED, base = "claude/12"), // also on #12
        )
        assertEquals(listOf(12, 14, 15, 16, 11), plan)
    }

    @Test fun aStackWhoseBaseIsNotReadyIsLeftOut() {
        val plan = order(
            ready(1, 11, 1),
            waiting(2, 12, MergeWait.CHECKS_RUNNING),
            waiting(3, 13, MergeWait.STACKED, base = "claude/12"),
            waiting(4, 14, MergeWait.STACKED, base = "claude/nobody"),
        )
        assertEquals(listOf(11), plan)
    }

    @Test fun notReadyPrsAreLeftOut() {
        val plan = order(
            ready(1, 11, 1),
            waiting(2, 12, MergeWait.CHECKS_FAILED),
            waiting(3, 13, MergeWait.CONFLICT),
            waiting(4, 14, MergeWait.DRAFT),
            waiting(5, null, MergeWait.NO_PR),
            waiting(6, 16, MergeWait.UPDATING),
        )
        assertEquals(listOf(11), plan)
    }

    @Test fun aSameNamedBranchInAnotherRepoIsNotAStack() {
        val plan = order(ready(1, 11, 1), waiting(2, 12, MergeWait.STACKED, repo = other, base = "claude/11"))
        assertEquals(listOf(11), plan)
    }

    @Test fun aTaskListedTwiceAndAStackLoopAreTakenOnce() {
        val plan = order(
            ready(1, 11, 1), ready(1, 11, 1),
            waiting(2, 12, MergeWait.STACKED, base = "claude/11"),
            waiting(3, 13, MergeWait.STACKED, base = "claude/12"),
        )
        assertEquals(listOf(11, 12, 13), plan)
    }

    @Test fun nothingReadyIsAnEmptyPlan() {
        assertTrue(MergeAllPlanner.order(emptyList()).isEmpty())
        assertTrue(order(waiting(1, 11, MergeWait.CHECKS_RUNNING), waiting(2, 12, MergeWait.STACKED, base = "claude/11")).isEmpty())
    }

    // --- the plan from GitHub ---

    @Test fun planningReadsTheBoardAndChecksEachTask() {
        val board = "$API_REPOS/VeganMarshmallow/claude-workspace"
        val repo = "$API_REPOS/VeganMarshmallow/Taskboard-widget"
        val body = "### Repo\n\nVeganMarshmallow/Taskboard-widget"
        val http = FakeHttp()
            .on({ it.url.startsWith("$board/issues?") && "state=open" in it.url }) {
                ok(JsonWriter.write(listOf(issueJson(30, "status: in-review") + ("body" to body), issueJson(29, "status: todo") + ("body" to body))))
            }
            .on({ it.url.startsWith("$board/issues?") }) { ok("[]") }
            .on({ it.url.startsWith("$repo/pulls?state=open") }) { ok(pullsPage(pullJson(12))) }
            .on({ it.url == "$repo/pulls/12" }) { ok(JsonWriter.write(pullJson(12))) }
            .on({ "/actions/runs?head_sha=" in it.url }) { ok(runsJson(passed("CI"))) }
            .on({ "/compare/" in it.url }) { ok("""{"behind_by":0}""") }
        val plan = MergeAllPlanning(GitHubApi(http, "t")).plan()
        assertEquals(MergeStep.Ok(listOf(PlannedMerge(30, "Task 30", PullRef(WIDGET, 12)))), plan)
    }

    @Test fun planningWithoutTheBoardSaysWhy() {
        val http = FakeHttp().on({ true }) { status(401) }
        val plan = MergeAllPlanning(GitHubApi(http, "t")).plan()
        assertTrue(plan is MergeStep.Stop && plan.problem is MergeProblem.Failed)
    }

    // --- when the widget offers it ---

    private fun boardWith(vararg lanes: MergeLane?): io.github.veganmarshmallow.taskboard.core.board.Board {
        val issues = lanes.indices.map { Issue(30 + it, "Task ${30 + it}", setOf(Labels.IN_REVIEW), true) }
        val board = BoardBuilder().build(issues, at(0))
        return board.withMergeLanes(lanes.withIndex().mapNotNull { (i, lane) -> lane?.let { 30 + i to it } }.toMap())
    }

    private fun lane(wait: MergeWait) = MergeLane(false, wait, "Task 30")

    @Test fun offeredForTwoOrMoreThatCanGoInOneRun() {
        val board = boardWith(MergeLane.NEXT, lane(MergeWait.QUEUED), lane(MergeWait.CHECKS_RUNNING))
        assertEquals(2, MergeAllOffer.count(board))
        assertTrue(MergeAllOffer.shows(board))
        assertTrue(MergeAllOffer.shows(boardWith(MergeLane.NEXT, lane(MergeWait.STACKED))))
    }

    @Test fun notOfferedForOneOrNoneReady() {
        assertFalse(MergeAllOffer.shows(null))
        assertFalse(MergeAllOffer.shows(boardWith(MergeLane.NEXT, lane(MergeWait.CHECKS_FAILED))))
        assertFalse("nothing to merge first", MergeAllOffer.shows(boardWith(lane(MergeWait.STACKED), lane(MergeWait.STACKED))))
        assertFalse("a board saved before #45 has no lanes", MergeAllOffer.shows(boardWith(null, null)))
    }

    // #60: the offer counts the plan the confirmation lists.

    private fun offered(vararg candidates: MergeCandidate): Int {
        val issues = candidates.map { Issue(it.issue, it.title, setOf(Labels.IN_REVIEW), true) }
        val board = BoardBuilder().build(issues, at(0)).withMergeLanes(MergeAllOffer.lanes(candidates.toList()))
        assertEquals("the offer's count is the plan's size", MergeAllPlanner.order(candidates.toList()).size, MergeAllOffer.count(board))
        return MergeAllOffer.count(board)
    }

    @Test fun aStackWhoseBaseIsNotInTheRunIsNotCounted() {
        val c = arrayOf(
            ready(30, 11, 1),
            waiting(31, 12, MergeWait.CHECKS_RUNNING),
            waiting(32, 13, MergeWait.STACKED, base = "claude/12"),
        )
        assertEquals("only #11; the dialog would list 1", 1, offered(*c))
        assertFalse(MergeAllOffer.lanes(c.toList()).getValue(32).inRun)
    }

    @Test fun aStackOnAReadyPrIsCounted() {
        assertEquals(2, offered(ready(30, 11, 1), waiting(31, 12, MergeWait.STACKED, base = "claude/11")))
        assertEquals("a stack of stacks", 3, offered(ready(30, 11, 1), waiting(31, 12, MergeWait.STACKED, base = "claude/11"), waiting(32, 13, MergeWait.STACKED, base = "claude/12")))
    }

    @Test fun readyPrsAreCountedAndWaitingOnesAreNot() {
        assertEquals(2, offered(ready(30, 11, 1), ready(31, 12, 2), waiting(32, 13, MergeWait.CHECKS_FAILED)))
        assertEquals(1, offered(ready(30, 11, 1), waiting(31, 14, MergeWait.STACKED, base = "claude/nobody")))
        assertEquals(1, offered(ready(30, 11, 1), waiting(31, 12, MergeWait.STACKED, repo = other, base = "claude/11")))
    }

    @Test fun nothingReadyCountsNothing() {
        assertEquals(0, offered())
        assertEquals(0, offered(waiting(30, 11, MergeWait.CHECKS_RUNNING), waiting(31, 12, MergeWait.STACKED, base = "claude/11")))
    }

    @Test fun twoStacksOnOneBaseAndATaskListedTwiceMatchThePlan() {
        assertEquals("both PRs stacked on #11", 3, offered(ready(30, 11, 1), waiting(31, 12, MergeWait.STACKED, base = "claude/11"), waiting(32, 13, MergeWait.STACKED, base = "claude/11")))
        val twice = listOf(ready(30, 11, 1), waiting(30, 11, MergeWait.CHECKS_FAILED), waiting(31, 12, MergeWait.STACKED, base = "claude/11"))
        val inRun = MergeAllOffer.lanes(twice).filterValues { it.inRun }.keys
        assertEquals("the first entry of a task listed twice counts, as in the plan", MergeAllPlanner.order(twice).map { it.issue }.toSet(), inRun)
        assertEquals(setOf(30, 31), inRun)
    }
}
