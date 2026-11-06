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
    /** When the run last changed: for a completed run, roughly when it finished (#45 "ready since"). */
    val updatedAt: Instant? = null,
    /** The run's `display_title` (the workflow's `run-name`); carries [WorkflowRuns.PARTIAL_MARK]. */
    val displayTitle: String? = null,
) {
    /**
     * A quick run that skipped part of its workflow's jobs on purpose (#52): not enough to merge on.
     * Only a title that *ends* with the marker counts: a workflow without `run-name` is titled with
     * its commit message, and "Fix [partial] sync" there must not hold the merge forever.
     */
    val partial: Boolean get() = displayTitle?.trimEnd()?.endsWith(WorkflowRuns.PARTIAL_MARK, ignoreCase = true) == true

    /**
     * GitHub is holding the run for someone's approval (#64): concluded `action_required` (it never
     * started: no jobs ran), or status `waiting` (an environment's reviewers).
     */
    val awaitingApproval: Boolean
        get() = conclusion.equals("action_required", ignoreCase = true) || status.equals("waiting", ignoreCase = true)

    /** Completed with `success` and ran its whole workflow: enough to stand in for a held run of that workflow (#64). */
    val passedFully: Boolean
        get() = status.equals("completed", ignoreCase = true) && conclusion.equals("success", ignoreCase = true) && !partial
}

/** What the workflow runs on a PR's head commit say about merging. */
sealed class ChecksVerdict {
    data object Passed : ChecksVerdict()
    data class Running(val names: List<String>) : ChecksVerdict()
    data class Failed(val names: List<String>) : ChecksVerdict()

    /** A run GitHub holds for approval, with no full success of its workflow on the commit to replace it (#64). */
    data class AwaitingApproval(val names: List<String>) : ChecksVerdict()

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
 * - Held for approval (#64): a run concluded `action_required` or with status `waiting`. When the
 *   same workflow has a full successful run on the commit from any event, it is superseded and
 *   ignored: the auto-update workflow's `pull_request` run is held (it came from GITHUB_TOKEN) while
 *   the build it dispatches tests the same commit. A partial success doesn't supersede it. Without
 *   such a success it is "Checks waiting for approval" (after failures and running runs): not failed.
 * - A run whose status is not `completed` (queued, in_progress, requested, pending, or anything
 *   unknown) is still running: "Checks still running".
 * - Completed with `success` passes. `neutral` and `skipped` neither pass nor block, but at least
 *   one kept run must have succeeded.
 * - Any other conclusion (`failure`, `cancelled`, `timed_out`, `stale`,
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
 * - Not started yet (#41): GitHub lists only the runs it has registered, so a workflow that normally
 *   runs for the PR but has no run on the head yet is still running ("<name> (not started)"). Which
 *   workflows are expected is [ExpectedWorkflows]; the caller passes their labels as `notStarted`.
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

    /**
     * [notStarted]: labels of workflows expected on [sha] that have no run yet ([ExpectedChecks], #41).
     * They count as still running, after failures and before the [PARTIAL_MARK] rule.
     */
    fun verdict(runs: List<WorkflowRun>, sha: String, notStarted: List<String> = emptyList()): ChecksVerdict {
        val newest = latest(runs, sha)
        if (newest.isEmpty()) return ChecksVerdict.None
        val kept = withoutSuperseded(newest)
        val (held, judged) = kept.partition { it.awaitingApproval }
        val (completed, running) = judged.partition { it.status.equals("completed", ignoreCase = true) }
        val failed = completed.filter { run -> run.conclusion?.lowercase().let { it != "success" && it !in NOT_BLOCKING } }
        val partialOnly = kept.groupBy(::workflowKey).values.filter { group -> group.all { it.partial } }
        return when {
            failed.isNotEmpty() -> ChecksVerdict.Failed(failed.map { it.name }.distinct())
            running.isNotEmpty() || notStarted.isNotEmpty() -> ChecksVerdict.Running((running.map { it.name } + notStarted).distinct())
            held.isNotEmpty() -> ChecksVerdict.AwaitingApproval(held.map { it.name }.distinct())
            partialOnly.isNotEmpty() -> ChecksVerdict.Running(partialOnly.map { "${it.first().name} (full run)" }.distinct())
            completed.none { it.conclusion.equals("success", ignoreCase = true) } -> ChecksVerdict.NothingPassed
            else -> ChecksVerdict.Passed
        }
    }

    /** Drops held runs ([WorkflowRun.awaitingApproval]) whose workflow passed fully on the commit from any event (#64). */
    private fun withoutSuperseded(kept: List<WorkflowRun>): List<WorkflowRun> {
        val passed = kept.filter { it.passedFully }.map(::workflowKey).toSet()
        return kept.filterNot { it.awaitingApproval && workflowKey(it) in passed }
    }

    private fun workflowKey(run: WorkflowRun): String = run.workflowId?.let { "id:$it" } ?: "name:${run.name}"
}
