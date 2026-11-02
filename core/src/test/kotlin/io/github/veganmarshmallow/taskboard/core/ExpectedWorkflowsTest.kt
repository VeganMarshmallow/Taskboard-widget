package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.merge.ChecksVerdict
import io.github.veganmarshmallow.taskboard.core.merge.ExpectedWorkflow
import io.github.veganmarshmallow.taskboard.core.merge.ExpectedWorkflows
import io.github.veganmarshmallow.taskboard.core.merge.Workflow
import io.github.veganmarshmallow.taskboard.core.merge.WorkflowList
import io.github.veganmarshmallow.taskboard.core.merge.WorkflowRun
import io.github.veganmarshmallow.taskboard.core.merge.WorkflowRuns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Which workflows a PR head still waits for before any run of them is listed (#41). */
class ExpectedWorkflowsTest {
    private var ids = 1L

    private fun run(
        name: String,
        workflow: Long?,
        sha: String = SHA_B,
        event: String? = "pull_request",
        created: String? = "2026-10-22T11:55:00Z",
        status: String = "completed",
        conclusion: String? = "success",
        title: String? = null,
        updated: String? = null,
    ) = WorkflowRun(ids++, workflow, name, sha, 1, 1, created?.let(Instant::parse), status, conclusion, event, updated?.let(Instant::parse), title)

    private val fast = ExpectedWorkflow(1, "Fast")
    private val slow = ExpectedWorkflow(2, "Slow")

    // --- expected: what ran for the PR before ---

    @Test fun pullRequestRunsOnEarlierCommitsAreExpected() {
        val history = listOf(run("Fast", 1), run("Slow", 2), run("Fast", 1, created = "2026-10-22T10:00:00Z"))
        assertEquals(listOf(fast, slow), ExpectedWorkflows.fromHistory(history, SHA_A))
    }

    @Test fun mainOnlyAndManualWorkflowsAreNotExpected() {
        val history = listOf(run("Deploy", 3, event = "push"), run("Release", 4, event = "workflow_dispatch"), run("Nightly", 5, event = "schedule"))
        assertEquals(emptyList<ExpectedWorkflow>(), ExpectedWorkflows.fromHistory(history, SHA_A))
    }

    @Test fun aPathFilteredWorkflowNeverSeenOnThePrIsNotExpected() {
        // "Docs" only runs when docs/ changes: it never ran for this PR, so it is not in the history.
        assertEquals(listOf(fast), ExpectedWorkflows.fromHistory(listOf(run("Fast", 1)), SHA_A))
        assertEquals("no history (a new PR, a new workflow): nothing expected", emptyList<ExpectedWorkflow>(), ExpectedWorkflows.fromHistory(emptyList(), SHA_A))
    }

    @Test fun runsOnTheHeadItselfOrWithoutAWorkflowIdAreNotHistory() {
        val history = listOf(run("Fast", 1, sha = SHA_A), run("Fast", 1, sha = SHA_A.uppercase()), run("Odd", null))
        assertEquals(emptyList<ExpectedWorkflow>(), ExpectedWorkflows.fromHistory(history, SHA_A))
    }

    @Test fun theNewestNameWinsAndEventCaseIsIgnored() {
        val history = listOf(run("Old name", 1, created = "2026-10-20T10:00:00Z"), run("Fast", 1, event = "PULL_REQUEST"), run("Untimed", 1, created = null))
        assertEquals(listOf(fast), ExpectedWorkflows.fromHistory(history, SHA_A))
    }

    // --- missing on the head ---

    @Test fun onlyAPullRequestRunOnTheHeadCountsAsPresent() {
        val head = listOf(run("Fast", 1, sha = SHA_A), run("Slow", 2, sha = SHA_A, event = "push"))
        assertEquals(listOf(slow), ExpectedWorkflows.missing(listOf(fast, slow), head, SHA_A))
        assertEquals(emptyList<ExpectedWorkflow>(), ExpectedWorkflows.missing(listOf(fast, slow), head + run("Slow", 2, sha = SHA_A), SHA_A))
    }

    @Test fun aRunStillQueuedOrFailedIsPresent() {
        val head = listOf(run("Fast", 1, sha = SHA_A, status = "queued", conclusion = null), run("Slow", 2, sha = SHA_A, conclusion = "failure"))
        assertEquals(emptyList<ExpectedWorkflow>(), ExpectedWorkflows.missing(listOf(fast, slow), head, SHA_A))
    }

    @Test fun runsOnAnotherCommitDontMakeAWorkflowPresent() {
        assertEquals(listOf(slow), ExpectedWorkflows.missing(listOf(slow), listOf(run("Slow", 2, sha = SHA_B)), SHA_A))
        assertEquals(emptyList<ExpectedWorkflow>(), ExpectedWorkflows.missing(emptyList(), emptyList(), SHA_A))
    }

    // --- still active ---

    @Test fun disabledAndDeletedWorkflowsAreNotWaitedFor() {
        listOf("disabled_manually", "disabled_inactivity", "disabled_fork", "deleted", "unknown").forEach { state ->
            val list = WorkflowList(listOf(Workflow(1, "Fast", "active"), Workflow(2, "Slow", state)), 2)
            assertEquals(state, listOf(fast), ExpectedWorkflows.stillActive(listOf(fast, slow), list))
        }
        assertEquals(listOf(slow), ExpectedWorkflows.stillActive(listOf(slow), WorkflowList(listOf(Workflow(2, "Slow", "ACTIVE")), 1)))
    }

    @Test fun aWorkflowMissingFromTheListIsDroppedOnlyWhenTheListIsComplete() {
        val complete = WorkflowList(listOf(Workflow(1, "Fast", "active")), 1)
        assertEquals(emptyList<ExpectedWorkflow>(), ExpectedWorkflows.stillActive(listOf(slow), complete))
        val morePages = WorkflowList(listOf(Workflow(1, "Fast", "active")), 150)
        assertEquals(listOf(slow), ExpectedWorkflows.stillActive(listOf(slow), morePages))
        val noTotal = WorkflowList(listOf(Workflow(1, "Fast", "active")), null)
        assertEquals(listOf(slow), ExpectedWorkflows.stillActive(listOf(slow), noTotal))
        assertEquals("no repo workflows listed at all", emptyList<ExpectedWorkflow>(), ExpectedWorkflows.stillActive(listOf(slow), WorkflowList(emptyList(), 0)))
    }

    @Test fun anUnreadableListKeepsEveryMissingWorkflow() =
        assertEquals(listOf(fast, slow), ExpectedWorkflows.stillActive(listOf(fast, slow), null))

    // --- grace ---

    @Test fun graceCountsFromTheHeadsFirstRun() {
        val now = Instant.parse("2026-10-22T12:00:00Z")
        fun within(vararg created: String?) = ExpectedWorkflows.withinGrace(created.map { run("Fast", 1, sha = SHA_A, created = it) }, SHA_A, now)
        assertTrue(within("2026-10-22T11:50:01Z"))
        assertFalse("exactly ten minutes is over", within("2026-10-22T11:50:00Z"))
        assertFalse("the first run sets the clock", within("2026-10-22T11:59:00Z", "2026-10-22T11:40:00Z"))
        assertTrue("a clock behind GitHub's is still within", within("2026-10-22T12:05:00Z"))
        assertTrue("an untimed run is ignored", within(null, "2026-10-22T11:55:00Z"))
        assertFalse("no time at all: no wait", within(null))
        assertFalse(within())
    }

    @Test fun graceUsesGitHubsTimesWhenTheDeviceClockIsBehind() {
        val behind = Instant.parse("2026-10-22T09:00:00Z") // device three hours slow
        fun within(updated: String) = ExpectedWorkflows.withinGrace(
            listOf(run("Fast", 1, sha = SHA_A, created = "2026-10-22T11:50:00Z", updated = updated)), SHA_A, behind)
        assertTrue("finished 5 min after it was created: still within", within("2026-10-22T11:55:00Z"))
        assertFalse("GitHub's own times show 10 min passed", within("2026-10-22T12:00:00Z"))
        assertFalse("any head run's update time counts", ExpectedWorkflows.withinGrace(
            listOf(run("Fast", 1, sha = SHA_A, created = "2026-10-22T11:50:00Z"), run("Slow", 2, sha = SHA_A, created = "2026-10-22T11:51:00Z", updated = "2026-10-22T12:30:00Z"),
                run("Fast", 1, sha = SHA_B, created = "2026-10-22T11:00:00Z", updated = "2026-10-22T13:00:00Z")), SHA_A, behind))
        assertTrue("other commits' times are ignored", ExpectedWorkflows.withinGrace(
            listOf(run("Fast", 1, sha = SHA_A, created = "2026-10-22T11:50:00Z"), run("Fast", 1, sha = SHA_B, created = "2026-10-22T11:00:00Z", updated = "2026-10-22T13:00:00Z")), SHA_A, behind))
    }

    @Test fun graceEndsEarlyWhenTheDeviceClockIsAhead() =
        assertFalse("fails open to the check before #41", ExpectedWorkflows.withinGrace(
            listOf(run("Fast", 1, sha = SHA_A, created = "2026-10-22T11:59:00Z")), SHA_A, Instant.parse("2026-10-22T15:00:00Z")))

    @Test fun graceIgnoresRunsOnOtherCommits() {
        val now = Instant.parse("2026-10-22T12:00:00Z")
        val runs = listOf(run("Fast", 1, sha = SHA_B, created = "2026-10-22T11:59:00Z"), run("Fast", 1, sha = SHA_A, created = "2026-10-22T11:00:00Z"))
        assertFalse(ExpectedWorkflows.withinGrace(runs, SHA_A, now))
    }

    // --- the verdict with workflows not started ---

    private fun verdict(runs: List<WorkflowRun>, notStarted: List<String>) = WorkflowRuns.verdict(runs, SHA_A, notStarted)

    private val label = ExpectedWorkflows.label(slow)

    @Test fun aWorkflowNotStartedKeepsAGreenHeadRunning() {
        assertEquals("Slow (not started)", label)
        assertEquals(ChecksVerdict.Running(listOf(label)), verdict(listOf(run("Fast", 1, sha = SHA_A)), listOf(label)))
        assertEquals(ChecksVerdict.Passed, verdict(listOf(run("Fast", 1, sha = SHA_A)), emptyList()))
    }

    @Test fun aFailureStillWinsAndRunningNamesComeFirst() {
        val failed = run("Fast", 1, sha = SHA_A, conclusion = "failure")
        assertEquals(ChecksVerdict.Failed(listOf("Fast")), verdict(listOf(failed), listOf(label)))
        val queued = run("Lint", 3, sha = SHA_A, status = "queued", conclusion = null)
        assertEquals(ChecksVerdict.Running(listOf("Lint", label)), verdict(listOf(queued), listOf(label, label)))
    }

    @Test fun notStartedComesBeforeThePartialRule() {
        val partial = run("build", 1, sha = SHA_A, event = "push", title = "build: x [partial]")
        assertEquals(ChecksVerdict.Running(listOf(label)), verdict(listOf(partial), listOf(label)))
    }

    @Test fun noRunsAtAllIsStillNone() = assertEquals(ChecksVerdict.None, verdict(emptyList(), listOf(label)))
}
