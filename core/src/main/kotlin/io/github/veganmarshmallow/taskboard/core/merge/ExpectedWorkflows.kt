package io.github.veganmarshmallow.taskboard.core.merge

import java.time.Duration
import java.time.Instant

/** One workflow from `GET /repos/{o}/{r}/actions/workflows`; [state] is `active`, `disabled_manually`, `deleted`, … */
data class Workflow(val id: Long, val name: String, val state: String) {
    val active: Boolean get() = state.equals("active", ignoreCase = true)
}

/** One page of workflows, and GitHub's total across all pages (null when not sent). */
data class WorkflowList(val workflows: List<Workflow>, val totalCount: Long?) {
    /** Every workflow of the repo is on this page, so one that is missing from it no longer exists. */
    val complete: Boolean get() = totalCount != null && workflows.size >= totalCount
}

/** A workflow that ran on an earlier commit of the PR, for the `pull_request` event. */
data class ExpectedWorkflow(val workflowId: Long, val name: String)

/**
 * Workflows that should have a run on a PR's head commit but don't yet (#41). [WorkflowRuns.verdict]
 * only sees the runs GitHub has registered: seconds after a push, a fast workflow can be green while
 * a slow one isn't listed yet, and that read as a pass.
 *
 * The rule:
 * - Expected: every workflow with a `pull_request` run on another commit of the PR's branch. So a
 *   push-to-main-only workflow, a `workflow_dispatch`-only one and one whose path filter never
 *   matched this PR are not expected. `pull_request` path filters compare the whole PR with its base,
 *   so a workflow that ran for an earlier head normally runs for the new one too.
 * - Present: the head commit has a `pull_request` run of that workflow (a `push` run doesn't count:
 *   the `pull_request` run tests the merge result).
 * - A workflow GitHub lists as not active (disabled, deleted), or that a complete workflow list
 *   doesn't have, isn't waited for.
 * - [GRACE]: GitHub registers a commit's runs within seconds to a few minutes of each other. Once
 *   the head's first run is older than that, a missing workflow is taken as not running for this
 *   commit (its trigger or path filter changed in the PR), so the Merge button never waits forever.
 */
object ExpectedWorkflows {
    const val PR_EVENT = "pull_request"
    val GRACE: Duration = Duration.ofMinutes(10)

    /** The workflows that ran for the PR on commits other than [sha], newest name first. */
    fun fromHistory(history: List<WorkflowRun>, sha: String): List<ExpectedWorkflow> = history
        .filter { it.workflowId != null && it.event.equals(PR_EVENT, ignoreCase = true) && !onCommit(it, sha) }
        .sortedWith(compareByDescending<WorkflowRun, Instant?>(nullsFirst()) { it.createdAt })
        .distinctBy { it.workflowId }
        .map { ExpectedWorkflow(it.workflowId!!, it.name) }

    /** The [expected] workflows without a `pull_request` run on [sha]. */
    fun missing(expected: List<ExpectedWorkflow>, headRuns: List<WorkflowRun>, sha: String): List<ExpectedWorkflow> {
        val present = headRuns
            .filter { onCommit(it, sha) && it.event.equals(PR_EVENT, ignoreCase = true) }
            .mapNotNull { it.workflowId }
            .toSet()
        return expected.filter { it.workflowId !in present }
    }

    /** Drops workflows [list] shows as not active, or doesn't have when it is complete. A null list keeps them all. */
    fun stillActive(missing: List<ExpectedWorkflow>, list: WorkflowList?): List<ExpectedWorkflow> {
        if (list == null) return missing
        val byId = list.workflows.associateBy { it.id }
        return missing.filter { byId[it.workflowId]?.active ?: !list.complete }
    }

    /** Whether [now] is less than [GRACE] after the first run on [sha]. False when no run has a time. */
    fun withinGrace(headRuns: List<WorkflowRun>, sha: String, now: Instant): Boolean {
        val first = headRuns.filter { onCommit(it, sha) }.mapNotNull { it.createdAt }.minOrNull() ?: return false
        return Duration.between(first, now) < GRACE
    }

    /** How a missing workflow is named in "Checks still running". */
    fun label(workflow: ExpectedWorkflow): String = "${workflow.name} (not started)"

    private fun onCommit(run: WorkflowRun, sha: String) = run.headSha != null && run.headSha.equals(sha, ignoreCase = true)
}

/**
 * Fetches what [ExpectedWorkflows] needs, only for a head that would otherwise pass: no extra call
 * once the head's first run is older than [ExpectedWorkflows.GRACE] (the usual merge), one for the
 * PR branch's `pull_request` runs, and one more for the workflow list only when something is missing.
 * If the branch's runs can't be read, nothing is expected (the check before #41). If the workflow
 * list can't be read, the missing ones stay expected; [ExpectedWorkflows.GRACE] still bounds the wait.
 */
class ExpectedChecks(private val pulls: PullsApi) {
    /** Labels of the workflows still to start on [pr]'s head, given the [headRuns] already fetched. */
    fun notStarted(pr: PullRequest, headRuns: List<WorkflowRun>): List<String> {
        if (!ExpectedWorkflows.withinGrace(headRuns, pr.headSha, pulls.now())) return emptyList()
        val history = (pulls.pullRequestRuns(pr.repo, pr.headRef) as? MergeStep.Ok)?.value ?: return emptyList()
        val missing = ExpectedWorkflows.missing(ExpectedWorkflows.fromHistory(history, pr.headSha), headRuns, pr.headSha)
        if (missing.isEmpty()) return emptyList()
        val list = (pulls.workflows(pr.repo) as? MergeStep.Ok)?.value
        return ExpectedWorkflows.stillActive(missing, list).map(ExpectedWorkflows::label).distinct()
    }
}
