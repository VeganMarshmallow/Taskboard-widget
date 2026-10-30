package io.github.veganmarshmallow.taskboard.core.merge

/** One check run on a commit. [conclusion] is null until the run has completed. */
data class CheckRun(val name: String, val status: String, val conclusion: String?)

/** What the check runs on a PR's head commit say about merging. */
sealed class ChecksVerdict {
    data object Passed : ChecksVerdict()
    data class Running(val names: List<String>) : ChecksVerdict()
    data class Failed(val names: List<String>) : ChecksVerdict()

    /** No check run has reported on the commit (CI not started yet, or the repo has none). */
    data object None : ChecksVerdict()

    /** Every run finished as neutral or skipped: nothing actually passed. */
    data object NothingPassed : ChecksVerdict()
}

/**
 * Decides whether the check runs allow a merge. The rules (task #30):
 *
 * - A run that has not completed (queued, in_progress, waiting, requested, pending, or anything
 *   unknown) is running.
 * - Completed with `success` passes. `neutral` and `skipped` neither pass nor fail: GitHub's own
 *   required checks treat them as not blocking, so they don't block here either, but at least one
 *   run must have succeeded.
 * - Any other conclusion (`failure`, `cancelled`, `timed_out`, `action_required`, `stale`,
 *   `startup_failure`, unknown, or missing) fails.
 * - A failure wins over running runs: the PR can't be merged as it is either way, and "failed"
 *   is the more useful thing to say. No runs at all refuses too: right after a push CI may not
 *   have registered yet, and merging unchecked code is what this guards against.
 */
object CheckRuns {
    private val NOT_BLOCKING = setOf("neutral", "skipped")

    fun verdict(runs: List<CheckRun>): ChecksVerdict {
        if (runs.isEmpty()) return ChecksVerdict.None
        val (completed, running) = runs.partition { it.status.equals("completed", ignoreCase = true) }
        val failed = completed.filter { run -> run.conclusion?.lowercase().let { it != "success" && it !in NOT_BLOCKING } }
        return when {
            failed.isNotEmpty() -> ChecksVerdict.Failed(failed.map { it.name }.distinct())
            running.isNotEmpty() -> ChecksVerdict.Running(running.map { it.name }.distinct())
            completed.none { it.conclusion.equals("success", ignoreCase = true) } -> ChecksVerdict.NothingPassed
            else -> ChecksVerdict.Passed
        }
    }
}
