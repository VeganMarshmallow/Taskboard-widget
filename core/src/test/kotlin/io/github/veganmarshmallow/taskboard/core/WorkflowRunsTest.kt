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
    ) = WorkflowRun(id, workflow, name, sha, number, attempt, created?.let(Instant::parse), status, conclusion)

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
        listOf("queued", "in_progress", "waiting", "requested", "pending", "unknown", "").forEach { status ->
            assertEquals(status, ChecksVerdict.Running(listOf("Lint")), verdict(run("CI"), run("Lint", null, status)))
        }
    }

    @Test fun everyBlockingConclusionFails() {
        listOf("failure", "cancelled", "timed_out", "action_required", "stale", "startup_failure", "weird", null).forEach { c ->
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
        assertEquals("missing or upper-case SHAs still count", ChecksVerdict.Passed, verdict(run("CI", sha = null), run("Lint", sha = SHA_A.uppercase())))
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
