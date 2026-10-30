package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.merge.CheckRun
import io.github.veganmarshmallow.taskboard.core.merge.CheckRuns
import io.github.veganmarshmallow.taskboard.core.merge.ChecksVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckRunsTest {
    private fun run(name: String, status: String = "completed", conclusion: String? = "success") = CheckRun(name, status, conclusion)
    private fun verdict(vararg runs: CheckRun) = CheckRuns.verdict(runs.toList())

    @Test fun noRunsAtAll() = assertEquals(ChecksVerdict.None, verdict())

    @Test fun allSucceeded() = assertEquals(ChecksVerdict.Passed, verdict(run("build"), run("emulator")))

    @Test fun neutralAndSkippedDoNotBlockAlongsideASuccess() {
        assertEquals(ChecksVerdict.Passed, verdict(run("build"), run("lint", conclusion = "neutral"), run("deploy", conclusion = "skipped")))
        assertEquals(ChecksVerdict.Passed, verdict(run("build"), run("x", conclusion = "SKIPPED")))
    }

    @Test fun onlyNeutralOrSkippedIsNotAPass() {
        assertEquals(ChecksVerdict.NothingPassed, verdict(run("a", conclusion = "skipped")))
        assertEquals(ChecksVerdict.NothingPassed, verdict(run("a", conclusion = "neutral"), run("b", conclusion = "skipped")))
    }

    @Test fun running() {
        listOf("queued", "in_progress", "waiting", "requested", "pending", "something_new").forEach { status ->
            assertEquals(ChecksVerdict.Running(listOf("emulator")), verdict(run("build"), run("emulator", status, null)))
        }
    }

    @Test fun failedConclusions() {
        listOf("failure", "cancelled", "timed_out", "action_required", "stale", "startup_failure", "weird", null).forEach { c ->
            assertEquals("$c", ChecksVerdict.Failed(listOf("build")), verdict(run("build", conclusion = c), run("lint")))
        }
    }

    @Test fun aFailureWinsOverRunningRuns() {
        assertEquals(ChecksVerdict.Failed(listOf("lint")), verdict(run("emulator", "in_progress", null), run("lint", conclusion = "failure")))
    }

    @Test fun duplicateNamesAreListedOnce() {
        assertEquals(ChecksVerdict.Failed(listOf("build")), verdict(run("build", conclusion = "failure"), run("build", conclusion = "failure")))
    }
}
