package io.github.veganmarshmallow.taskboard.core.merge

import java.time.Instant

/**
 * One GitHub Actions workflow run on a commit, from `GET /repos/{o}/{r}/actions/runs?head_sha=…`.
 * [conclusion] is null until the run has completed. [workflowId] and [event] group re-runs of the
 * same workflow; [id] identifies the run itself (to drop duplicates across pages).
 */
data class WorkflowRun(
    val id: Long?,
    val workflowId: Long?,
    val name: String,
    val headSha: String?,
    val runNumber: Long,
    val runAttempt: Long,
    val createdAt: Instant?,
    val status: String,
    val conclusion: String?,
    val event: String? = null,
    /** The run's `display_title` (the workflow's `run-name`); carries [WorkflowRuns.PARTIAL_MARK]. */
    val displayTitle: String? = null,
) {
    /**
     * A quick run that skipped part of its workflow's jobs on purpose (#52): not enough to merge on.
     * Only a title that *ends* with the marker counts: a workflow without `run-name` is titled with
     * its commit message, and "Fix [partial] sync" there must not hold the merge forever.
     */
    val partial: Boolean get() = displayTitle?.trimEnd()?.endsWith(WorkflowRuns.PARTIAL_MARK, ignoreCase = true) == true
}

/** What the workflow runs on a PR's head commit say about merging. */
sealed class ChecksVerdict {
    data object Passed : ChecksVerdict()
    data class Running(val names: List<String>) : ChecksVerdict()
    data class Failed(val names: List<String>) : ChecksVerdict()

    /** No workflow run on the commit (CI not started yet, or the repo has none). */
    data object None : ChecksVerdict()

    /** Every kept run finished as neutral or skipped: nothing actually passed. */
    data object NothingPassed : ChecksVerdict()
}

/**
 * Decides whether a PR's GitHub Actions runs allow a merge (task #38; it replaced check runs,
 * which fine-grained tokens can't read). Only the Actions: Read-only permission is needed.
 *
 * Run selection ([latest]): only runs whose `head_sha` is this commit count (GitHub already filters
 * on `head_sha`; this is a second guard, and the parser refuses a run without one). Then for each
 * workflow and triggering event (`workflow_id`, or the name for a run without one, plus `event`)
 * only the newest run counts: highest `run_number`, then `run_attempt`, then newest `created_at`,
 * then highest `id`. So a run that failed and was later re-run green, or superseded by a new run of
 * the same workflow and event, doesn't block. A run from another event (say `pull_request`, which
 * tests the merge result, next to `push`) is judged on its own: a green push run can't hide it.
 *
 * The rule ([verdict]), on the kept runs:
 * - A run whose status is not `completed` (queued, in_progress, waiting, requested, pending, or
 *   anything unknown) is still running: "Checks still running".
 * - Completed with `success` passes. `neutral` and `skipped` neither pass nor block, but at least
 *   one kept run must have succeeded.
 * - Any other conclusion (`failure`, `cancelled`, `timed_out`, `action_required`, `stale`,
 *   `startup_failure`, unknown, or missing) fails: "Checks failed".
 * - A failure wins over running runs: the PR can't be merged as it is either way, and "failed" is
 *   the more useful thing to say.
 * - No runs at all refuses: right after a push CI may not have registered yet, and merging
 *   unchecked code is what this guards against.
 * - Partial runs (#52): a run whose title ends with [PARTIAL_MARK] ran only part of its workflow (the
 *   repo's CI runs only `build` on a plain push; emulator tests run on the PR's `pull_request` run).
 *   It still fails or blocks as usual, but a workflow with nothing but partial runs on the commit
 *   isn't a pass: it counts as still running ("<name> (full run)") until a full run of that workflow,
 *   from any event, shows up. Workflows that never use the marker are unaffected.
 */
object WorkflowRuns {
    /** Marks a partial run's title; see `run-name` in .github/workflows/build.yml. */
    const val PARTIAL_MARK = "[partial]"

    private val NOT_BLOCKING = setOf("neutral", "skipped")

    private val NEWEST_FIRST = compareByDescending<WorkflowRun> { it.runNumber }
        .thenByDescending { it.runAttempt }
        .thenByDescending(nullsFirst()) { it.createdAt }
        .thenByDescending(nullsFirst()) { it.id }

    /** The newest run of each workflow and event on [sha], in the order they first appear. */
    fun latest(runs: List<WorkflowRun>, sha: String): List<WorkflowRun> = runs
        .filter { it.headSha != null && it.headSha.equals(sha, ignoreCase = true) }
        .groupBy { workflowKey(it) to it.event?.lowercase() }
        .values
        .map { group -> group.sortedWith(NEWEST_FIRST).first() }

    fun verdict(runs: List<WorkflowRun>, sha: String): ChecksVerdict {
        val kept = latest(runs, sha)
        if (kept.isEmpty()) return ChecksVerdict.None
        val (completed, running) = kept.partition { it.status.equals("completed", ignoreCase = true) }
        val failed = completed.filter { run -> run.conclusion?.lowercase().let { it != "success" && it !in NOT_BLOCKING } }
        val partialOnly = kept.groupBy(::workflowKey).values.filter { group -> group.all { it.partial } }
        return when {
            failed.isNotEmpty() -> ChecksVerdict.Failed(failed.map { it.name }.distinct())
            running.isNotEmpty() -> ChecksVerdict.Running(running.map { it.name }.distinct())
            partialOnly.isNotEmpty() -> ChecksVerdict.Running(partialOnly.map { "${it.first().name} (full run)" }.distinct())
            completed.none { it.conclusion.equals("success", ignoreCase = true) } -> ChecksVerdict.NothingPassed
            else -> ChecksVerdict.Passed
        }
    }

    private fun workflowKey(run: WorkflowRun): String = run.workflowId?.let { "id:$it" } ?: "name:${run.name}"
}
