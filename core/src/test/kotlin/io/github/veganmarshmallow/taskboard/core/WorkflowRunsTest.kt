package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.merge.ChecksVerdict
import io.github.veganmarshmallow.taskboard.core.merge.WorkflowRun
import io.github.veganmarshmallow.taskboard.core.merge.WorkflowRuns
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** Run selection (newest per workflow) and the merge verdict on Actions runs (task #38). */
class WorkflowRunsTest {
    private var ids = 1L

    private fun run(
        name: String = "CI",
        conclusion: String? = "success",
        status: String = "completed",
        workflow: Long? = name.length.toLong() * 100 + name.first().code,
        number: Long = 1,
        attempt: Long = 1,
        created: String? = "2026-10-30T10:00:00Z",
        sha: String? = SHA_A,
        id: Long? = ids++,
        event: String? = "push",
        title: String? = null,
    ) = WorkflowRun(id, workflow, name, sha, number, attempt, created?.let(Instant::parse), status, conclusion, event, displayTitle = title)

    private fun partial(conclusion: String? = "success", status: String = "completed", event: String = "push", number: Long = 1) =
        run("build", conclusion, status, workflow = 1, number = number, event = event, title = "build: claude/52-x [partial]")

    private fun full(conclusion: String? = "success", status: String = "completed", event: String = "pull_request", number: Long = 2) =
        run("build", conclusion, status, workflow = 1, number = number, event = event, title = "build: claude/52-x")

    private val waitingForFull = ChecksVerdict.Running(listOf("build (full run)"))

    private fun verdict(vararg runs: WorkflowRun) = WorkflowRuns.verdict(runs.toList(), SHA_A)

    // --- the rule ---

    @Test fun noRunsAtAllRefuses() = assertEquals(ChecksVerdict.None, verdict())

    @Test fun allSucceeded() = assertEquals(ChecksVerdict.Passed, verdict(run("CI"), run("Lint")))

    @Test fun skippedAndNeutralDontBlockAlongsideASuccess() {
        assertEquals(ChecksVerdict.Passed, verdict(run("CI"), run("Docs", "neutral"), run("Deploy", "skipped")))
        assertEquals(ChecksVerdict.Passed, verdict(run("CI", "SUCCESS", "COMPLETED"), run("Deploy", "Skipped")))
    }

    @Test fun onlySkippedOrNeutralIsNotAPass() {
        assertEquals(ChecksVerdict.NothingPassed, verdict(run("Deploy", "skipped")))
        assertEquals(ChecksVerdict.NothingPassed, verdict(run("Docs", "neutral"), run("Deploy", "skipped")))
    }

    @Test fun anythingNotCompletedIsRunning() {
        listOf("queued", "in_progress", "requested", "pending", "unknown", "").forEach { status ->
            assertEquals(status, ChecksVerdict.Running(listOf("Lint")), verdict(run("CI"), run("Lint", null, status)))
        }
    }

    @Test fun everyBlockingConclusionFails() {
        listOf("failure", "cancelled", "timed_out", "stale", "startup_failure", "weird", null).forEach { c ->
            assertEquals("$c", ChecksVerdict.Failed(listOf("CI")), verdict(run("CI", c), run("Lint")))
        }
    }

    @Test fun aFailureWinsOverARunningWorkflow() {
        assertEquals(ChecksVerdict.Failed(listOf("Lint")), verdict(run("CI", null, "in_progress"), run("Lint", "failure")))
    }

    @Test fun namesAreListedOncePerName() {
        val a = run("CI", "failure", workflow = 1)
        val b = run("CI", "timed_out", workflow = 2) // two workflow files with the same display name
        assertEquals(ChecksVerdict.Failed(listOf("CI")), verdict(a, b))
    }

    // --- run selection ---

    @Test fun aFailedRunReRunGreenIsAPassByAttempt() {
        val first = run("CI", "failure", attempt = 1, created = "2026-10-30T10:00:00Z")
        val rerun = run("CI", "success", attempt = 2, created = "2026-10-30T09:00:00Z") // attempt beats time
        assertEquals(ChecksVerdict.Passed, verdict(first, rerun))
        assertEquals(ChecksVerdict.Passed, verdict(rerun, first))
    }

    @Test fun aGreenRunReRunThatFailsBlocks() {
        assertEquals(ChecksVerdict.Failed(listOf("CI")), verdict(run("CI", attempt = 1), run("CI", "failure", attempt = 2)))
    }

    @Test fun aNewerRunNumberWinsOverAHigherAttempt() {
        val old = run("CI", "success", number = 7, attempt = 3)
        val newer = run("CI", null, "queued", number = 8, attempt = 1)
        assertEquals(ChecksVerdict.Running(listOf("CI")), verdict(old, newer))
    }

    @Test fun createdAtThenIdBreakTies() {
        val early = run("CI", "failure", created = "2026-10-30T10:00:00Z")
        val late = run("CI", "success", created = "2026-10-30T10:05:00Z")
        assertEquals(ChecksVerdict.Passed, verdict(late, early))
        val noTime = run("CI", "failure", created = null)
        assertEquals("a known time beats a missing one", ChecksVerdict.Passed, verdict(noTime, late))
        assertEquals(ChecksVerdict.Failed(listOf("CI")), verdict(run("CI", id = 5), run("CI", "failure", id = 9)))
    }

    @Test fun severalWorkflowsEachKeepTheirNewestRun() {
        val runs = listOf(
            run("CI", "failure", workflow = 1, attempt = 1), run("CI", workflow = 1, attempt = 2),
            run("Lint", workflow = 2, number = 3), run("Lint", null, "in_progress", workflow = 2, number = 4),
            run("Deploy", "skipped", workflow = 3),
        )
        val kept = WorkflowRuns.latest(runs, SHA_A)
        assertEquals(listOf(1L to "success", 2L to "in_progress", 3L to "skipped"), kept.map { it.workflowId to (it.conclusion ?: it.status) })
        assertEquals(ChecksVerdict.Running(listOf("Lint")), WorkflowRuns.verdict(runs, SHA_A))
    }

    @Test fun runsWithoutAWorkflowIdAreGroupedByName() {
        val runs = listOf(run("CI", "failure", workflow = null, attempt = 1), run("CI", workflow = null, attempt = 2), run("Lint", "failure", workflow = null))
        assertEquals(2, WorkflowRuns.latest(runs, SHA_A).size)
        assertEquals(ChecksVerdict.Failed(listOf("Lint")), WorkflowRuns.verdict(runs, SHA_A))
    }

    @Test fun runsForAnotherCommitAreIgnored() {
        assertEquals(ChecksVerdict.None, verdict(run("CI", sha = SHA_B)))
        assertEquals(ChecksVerdict.Passed, verdict(run("CI"), run("Lint", "failure", sha = SHA_B)))
        assertEquals("upper-case SHAs still count", ChecksVerdict.Passed, verdict(run("CI"), run("Lint", sha = SHA_A.uppercase())))
        assertEquals("a run without a SHA can't pass", ChecksVerdict.None, verdict(run("CI", sha = null)))
        assertEquals(ChecksVerdict.None, verdict(run("CI", sha = "")))
        assertEquals("a prefix isn't the commit", ChecksVerdict.None, verdict(run("CI", sha = SHA_A.dropLast(1))))
    }

    // --- events (task #38 review) ---

    @Test fun aGreenPushRunDoesNotHideAFailedPullRequestRunOfTheSameWorkflow() {
        // Same workflow, same SHA, two triggers: the newer push run must not supersede the pull_request run.
        val runs = arrayOf(run("CI", "failure", workflow = 1, number = 7, event = "pull_request"), run("CI", workflow = 1, number = 8, event = "push"))
        assertEquals(2, WorkflowRuns.latest(runs.toList(), SHA_A).size)
        assertEquals(ChecksVerdict.Failed(listOf("CI")), verdict(*runs))
        assertEquals(ChecksVerdict.Failed(listOf("CI")), verdict(*runs.reversedArray()))
    }

    @Test fun aRunningRunFromAnotherEventStillBlocks() {
        assertEquals(
            ChecksVerdict.Running(listOf("CI")),
            verdict(run("CI", null, "in_progress", workflow = 1, number = 3, event = "workflow_dispatch"), run("CI", workflow = 1, number = 9, event = "push")),
        )
    }

    @Test fun reRunsAreStillJudgedWithinTheirOwnEvent() {
        val runs = arrayOf(
            run("CI", "failure", workflow = 1, number = 7, event = "pull_request"),
            run("CI", workflow = 1, number = 7, attempt = 2, event = "pull_request"),
            run("CI", workflow = 1, number = 8, event = "PUSH"),
            run("CI", "failure", workflow = 1, number = 5, event = "push"),
        )
        assertEquals(ChecksVerdict.Passed, verdict(*runs))
        assertEquals("a missing event is its own group", ChecksVerdict.Failed(listOf("CI")), verdict(run("CI", workflow = 1, number = 9), run("CI", "failure", workflow = 1, number = 2, event = null)))
    }

    // --- runs held for approval (#64: the auto-update's pull_request run, from GITHUB_TOKEN) ---

    /** The held pull_request run as GitHub reports it: completed, `action_required`, titled with the PR title (no run-name). */
    private fun held(number: Long = 329, workflow: Long = 1) =
        run("build", "action_required", workflow = workflow, number = number, event = "pull_request", title = "Merge next (#45)")

    private fun dispatched(conclusion: String? = "success", status: String = "completed", number: Long = 330, attempt: Long = 1) =
        run("build", conclusion, status, workflow = 1, number = number, attempt = attempt, event = "workflow_dispatch", title = "build: claude/45-x")

    private val awaiting = ChecksVerdict.AwaitingApproval(listOf("build"))

    @Test fun aHeldRunNextToAGreenDispatchedBuildOfTheSameWorkflowPasses() {
        // PR #45, head ea4b1ad: run 329 (pull_request, action_required) and run 330 (workflow_dispatch, success).
        assertEquals(ChecksVerdict.Passed, verdict(held(), dispatched()))
        assertEquals("order doesn't matter", ChecksVerdict.Passed, verdict(dispatched(), held()))
    }

    @Test fun aHeldRunAloneWaitsForApprovalAndIsNotFailed() {
        assertEquals(awaiting, verdict(held()))
        assertEquals("upper case", awaiting, verdict(run("build", "ACTION_REQUIRED", workflow = 1, event = "pull_request")))
    }

    @Test fun aWaitingStatusIsHeldForApprovalToo() {
        val waiting = run("deploy", null, "waiting", workflow = 9, event = "pull_request")
        assertEquals(ChecksVerdict.AwaitingApproval(listOf("deploy")), verdict(waiting))
        assertEquals("superseded by a success of the same workflow", ChecksVerdict.Passed, verdict(waiting, run("deploy", workflow = 9, number = 2, event = "workflow_dispatch")))
    }

    @Test fun aHeldRunNextToAFailedBuildFails() {
        assertEquals(ChecksVerdict.Failed(listOf("build")), verdict(held(), dispatched("failure")))
    }

    @Test fun aHeldRunNextToARunningBuildIsStillRunning() {
        assertEquals(ChecksVerdict.Running(listOf("build")), verdict(held(), dispatched(null, "in_progress")))
        assertEquals(ChecksVerdict.Running(listOf("build")), verdict(held(), dispatched(null, "queued")))
    }

    @Test fun aPartialSuccessDoesNotSupersedeAHeldRun() {
        assertEquals(awaiting, verdict(held(), partial()))
        assertEquals(awaiting, verdict(held(), partial(event = "workflow_dispatch", number = 330)))
    }

    @Test fun anotherWorkflowsSuccessDoesNotSupersedeAHeldRun() {
        assertEquals(ChecksVerdict.AwaitingApproval(listOf("build")), verdict(held(workflow = 1), run("Lint", workflow = 2)))
    }

    @Test fun aSuccessOnAnotherCommitDoesNotSupersedeAHeldRun() {
        assertEquals(awaiting, verdict(held(), run("build", workflow = 1, number = 330, event = "workflow_dispatch", sha = SHA_B)))
    }

    @Test fun skippedOrNeutralDoNotSupersedeAHeldRun() {
        assertEquals(awaiting, verdict(held(), dispatched("skipped")))
        assertEquals(awaiting, verdict(held(), dispatched("neutral")))
    }

    @Test fun aFailureOfAnotherWorkflowStillWinsOverASupersededHeldRun() {
        assertEquals(ChecksVerdict.Failed(listOf("Lint")), verdict(held(), dispatched(), run("Lint", "failure", workflow = 2)))
        assertEquals(ChecksVerdict.Running(listOf("Lint")), verdict(held(), dispatched(), run("Lint", null, "in_progress", workflow = 2)))
    }

    @Test fun aFailureWinsOverAnUnsupersededHeldRun() {
        assertEquals(ChecksVerdict.Failed(listOf("Lint")), verdict(held(), run("Lint", "failure", workflow = 2)))
        assertEquals("running wins too", ChecksVerdict.Running(listOf("Lint")), verdict(held(), run("Lint", null, "in_progress", workflow = 2)))
    }

    @Test fun reRunsCombineWithTheHeldRule() {
        // The dispatched build failed, then its re-run passed: the newest attempt supersedes the held run.
        assertEquals(ChecksVerdict.Passed, verdict(held(), dispatched("failure"), dispatched(attempt = 2)))
        // It passed, then a re-run failed: the newest attempt counts, so it fails.
        assertEquals(ChecksVerdict.Failed(listOf("build")), verdict(held(), dispatched(), dispatched("failure", attempt = 2)))
        // The held run itself approved and re-run green later (newer attempt in its own event): a plain pass.
        val approved = run("build", workflow = 1, number = 329, attempt = 2, event = "pull_request", title = "build: claude/45-x")
        assertEquals(ChecksVerdict.Passed, verdict(held(), approved))
    }

    @Test fun aNewerHeldRunOfTheSameEventReplacesAnOlderGreenOne() {
        // Per event, the newest run counts: the green pull_request run is replaced by the newer held one,
        // so only another event's success can supersede it.
        val oldGreen = full(number = 300)
        assertEquals(awaiting, verdict(oldGreen, held(number = 329)))
        assertEquals(ChecksVerdict.Passed, verdict(oldGreen, held(number = 329), dispatched()))
    }

    @Test fun aGreenPushRunThatIsFullSupersedesAHeldRun() {
        // Any event counts, as long as the run was full (main-style full push run).
        assertEquals(ChecksVerdict.Passed, verdict(held(), run("build", workflow = 1, number = 331, event = "push", title = "build: main")))
    }

    @Test fun twoHeldWorkflowsAreNamedOnce() {
        val a = held(workflow = 1)
        val b = run("build", "action_required", workflow = 2, event = "pull_request")
        assertEquals(awaiting, verdict(a, b))
    }

    // --- partial runs (#52: plain pushes run only `build`; emulator tests run on the PR's pull_request run) ---

    @Test fun aGreenPartialPushRunAloneIsNotAPass() {
        // A ready PR right after a push: the push run finished, the pull_request run isn't registered yet.
        assertEquals(waitingForFull, verdict(partial()))
    }

    @Test fun aGreenFullPullRequestRunNextToThePartialPushRunPasses() {
        assertEquals(ChecksVerdict.Passed, verdict(partial(), full()))
        assertEquals(ChecksVerdict.Passed, verdict(full(), partial()))
    }

    @Test fun aFullRunFromWorkflowDispatchAlsoCounts() {
        // update-prs.js re-runs CI after updating a branch with GITHUB_TOKEN, which starts no push or pull_request run.
        assertEquals(ChecksVerdict.Passed, verdict(full(event = "workflow_dispatch")))
        assertEquals(ChecksVerdict.Passed, verdict(partial(), full(event = "workflow_dispatch")))
    }

    @Test fun theFullRunStillRunningOrFailingBlocks() {
        assertEquals(ChecksVerdict.Running(listOf("build")), verdict(partial(), full(null, "in_progress")))
        assertEquals(ChecksVerdict.Failed(listOf("build")), verdict(partial(), full("failure")))
        assertEquals(ChecksVerdict.Failed(listOf("build")), verdict(partial(), full("cancelled")))
    }

    @Test fun aFailedOrRunningPartialRunStillBlocks() {
        assertEquals(ChecksVerdict.Failed(listOf("build")), verdict(partial("failure"), full()))
        assertEquals(ChecksVerdict.Failed(listOf("build")), verdict(partial("failure")))
        assertEquals(ChecksVerdict.Running(listOf("build")), verdict(partial(null, "queued")))
    }

    @Test fun onlyPartialRunsFromSeveralEventsStillWait() {
        // A draft PR: partial push run plus a pull_request run with no jobs (also marked partial), or a dispatch with full=false.
        assertEquals(waitingForFull, verdict(partial(), partial("skipped", event = "pull_request", number = 2)))
        assertEquals(waitingForFull, verdict(partial(), partial(event = "workflow_dispatch", number = 3)))
    }

    @Test fun aNewerPartialRerunOfTheSameEventReplacesTheOlderFullOne() {
        // Newest per workflow and event wins, so a newer partial dispatch hides an older full dispatch.
        assertEquals(waitingForFull, verdict(full(event = "workflow_dispatch", number = 2), partial(event = "workflow_dispatch", number = 3)))
    }

    @Test fun anotherWorkflowsFullRunDoesNotCoverAPartialOne() {
        val other = run("Docs", workflow = 2, event = "pull_request")
        assertEquals(waitingForFull, verdict(partial(), other))
    }

    @Test fun theMarkerIsCaseInsensitiveAndOnlyInTheTitle() {
        assertEquals(waitingForFull, verdict(run("build", workflow = 1, title = "build: x [PARTIAL]")))
        assertEquals("the workflow name doesn't count", ChecksVerdict.Passed, verdict(run("build [partial]", workflow = 1)))
        assertEquals("no title is a full run", ChecksVerdict.Passed, verdict(run("build", workflow = 1, title = null)))
        assertEquals(ChecksVerdict.Passed, verdict(run("build", workflow = 1, title = "build: partial-fix")))
    }

    @Test fun aCommitMessageMentioningTheMarkerIsNotPartial() {
        // Workflows without run-name are titled with the commit message: only a trailing marker counts.
        assertEquals(ChecksVerdict.Passed, verdict(run("CI", workflow = 1, title = "Fix [partial] sync of lists")))
        assertEquals(waitingForFull, verdict(run("build", workflow = 1, title = "build: claude/52-x [partial]  ")))
        assertEquals(ChecksVerdict.Passed, verdict(run("build", workflow = 1, title = "")))
    }

    @Test fun reRunAttemptsOfTheFullRunAreJudgedByTheNewestAttempt() {
        // Flaky emulator job: attempt 1 failed, rerun-failed-jobs gave attempt 2 (same run number).
        val attempt1 = run("build", "failure", workflow = 1, number = 2, attempt = 1, event = "pull_request", title = "build: claude/52-x")
        val attempt2 = run("build", "success", workflow = 1, number = 2, attempt = 2, event = "pull_request", title = "build: claude/52-x")
        assertEquals(ChecksVerdict.Passed, verdict(partial(), attempt1, attempt2))
        val rerunning = run("build", null, "in_progress", workflow = 1, number = 2, attempt = 2, event = "pull_request", title = "build: claude/52-x")
        assertEquals(ChecksVerdict.Running(listOf("build")), verdict(partial(), attempt1, rerunning))
    }

    @Test fun aDraftTurnedReadyOnTheSameCommitPassesOnceItsFullRunIsGreen() {
        // opened as draft (empty pull_request run, partial), then ready_for_review (full) on the same head.
        val draftRun = partial("skipped", event = "pull_request", number = 2)
        assertEquals(waitingForFull, verdict(partial(), draftRun))
        assertEquals(ChecksVerdict.Passed, verdict(partial(), draftRun, full(number = 3)))
    }

    @Test fun hebrewAndHugeNamesAreKept() {
        val long = "ב".repeat(5_000)
        assertEquals(ChecksVerdict.Failed(listOf("בדיקות ✅", long)), verdict(run("בדיקות ✅", "failure"), run(long, "cancelled")))
    }

    @Test fun manyRunsKeepOnlyEachWorkflowsNewest() {
        // 5000 runs over 50 workflows; run 4999 is W49's newest and failed, W0's old failure was superseded.
        val runs = (1..5_000).map { n ->
            run("W${n % 50}", if (n == 4_999 || n == 50) "failure" else "success", workflow = (n % 50).toLong(), number = n.toLong())
        }
        assertEquals(50, WorkflowRuns.latest(runs, SHA_A).size)
        assertEquals(ChecksVerdict.Failed(listOf("W49")), WorkflowRuns.verdict(runs, SHA_A))
    }
}
