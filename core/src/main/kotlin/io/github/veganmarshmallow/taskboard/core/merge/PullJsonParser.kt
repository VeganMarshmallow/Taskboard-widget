package io.github.veganmarshmallow.taskboard.core.merge

import io.github.veganmarshmallow.taskboard.core.github.MalformedResponseException
import io.github.veganmarshmallow.taskboard.core.json.JsonParseException
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.json.list
import io.github.veganmarshmallow.taskboard.core.json.long
import io.github.veganmarshmallow.taskboard.core.json.string
import io.github.veganmarshmallow.taskboard.core.model.GitHubNames
import io.github.veganmarshmallow.taskboard.core.model.Repo
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Parses the pulls, workflow-runs and merge responses. Throws [MalformedResponseException] when a
 * field merging depends on (number, state, head SHA, base ref) is missing or has the wrong type.
 * The PR's repo is the one that was asked, never read from the response.
 */
object PullJsonParser {
    private val SHA = GitHubNames.SHA

    fun parsePull(body: String, repo: Repo): PullRequest =
        pull(parse(body) as? Map<*, *> ?: throw MalformedResponseException("Expected a JSON pull request"), repo)

    /** A list of PRs. An element that is not a usable PR is an error, like the issue lists. */
    fun parsePulls(body: String, repo: Repo): List<PullRequest> {
        val array = parse(body) as? List<*> ?: throw MalformedResponseException("Expected a JSON array of pull requests")
        return array.map { pull(it as? Map<*, *> ?: throw MalformedResponseException("PR is not an object"), repo) }
    }

    /**
     * `{"total_count": n, "workflow_runs": [...]}` from the Actions runs list. A run without a name
     * is kept as "workflow"; a run without `head_sha` is malformed (it can't be tied to the commit
     * being merged); a missing status is "unknown" (counted as still running), and an
     * unreadable `created_at` is null (it only breaks ties between attempts).
     */
    fun parseWorkflowRuns(body: String): WorkflowRunPage {
        val obj = parse(body) as? Map<*, *> ?: throw MalformedResponseException("Expected a workflow-runs object")
        val runs = obj.list("workflow_runs") ?: throw MalformedResponseException("Missing workflow_runs")
        return WorkflowRunPage(
            runs.map { raw ->
                val run = raw as? Map<*, *> ?: throw MalformedResponseException("Workflow run is not an object")
                WorkflowRun(
                    id = run.long("id"),
                    workflowId = run.long("workflow_id"),
                    name = run.string("name")?.takeIf { it.isNotBlank() } ?: "workflow",
                    headSha = run.string("head_sha") ?: throw MalformedResponseException("Workflow run without head_sha"),
                    runNumber = run.long("run_number") ?: 0,
                    runAttempt = run.long("run_attempt") ?: 1,
                    createdAt = run.string("created_at")?.let(::instant),
                    status = run.string("status") ?: "unknown",
                    conclusion = run.string("conclusion"),
                    event = run.string("event"),
                    updatedAt = run.string("updated_at")?.let(::instant),
                    displayTitle = run.string("display_title"),
                )
            },
            obj.long("total_count")?.takeIf { it >= 0 },
        )
    }

    /**
     * `{"total_count": n, "workflows": [...]}` from the Actions workflows list (#41). A workflow
     * without an id is dropped (it can't be matched to a run); a missing state is "unknown" (not active).
     */
    fun parseWorkflows(body: String): WorkflowList {
        val obj = parse(body) as? Map<*, *> ?: throw MalformedResponseException("Expected a workflows object")
        val workflows = obj.list("workflows") ?: throw MalformedResponseException("Missing workflows")
        return WorkflowList(
            workflows.mapNotNull { raw ->
                val wf = raw as? Map<*, *> ?: throw MalformedResponseException("Workflow is not an object")
                val id = wf.long("id") ?: return@mapNotNull null
                Workflow(id, wf.string("name") ?: "workflow", wf.string("state") ?: "unknown")
            },
            obj.long("total_count")?.takeIf { it >= 0 },
        )
    }

    /** `behind_by` from a compare response; malformed when missing or negative. */
    fun behindBy(body: String): Long =
        (parse(body) as? Map<*, *>)?.long("behind_by")?.takeIf { it >= 0 } ?: throw MalformedResponseException("Compare without behind_by")

    /** GitHub's `message` from an error or merge response; null when there is none. */
    fun message(body: String): String? = try {
        (parse(body) as? Map<*, *>)?.string("message")?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: MalformedResponseException) {
        null
    }

    /** The merge response's `merged` flag; false when absent. */
    fun merged(body: String): Boolean = try {
        (parse(body) as? Map<*, *>)?.get("merged") == true
    } catch (e: MalformedResponseException) {
        false
    }

    private fun pull(obj: Map<*, *>, repo: Repo): PullRequest {
        val number = obj.long("number")?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
            ?: throw MalformedResponseException("PR without a valid number")
        val state = obj.string("state") ?: throw MalformedResponseException("PR #$number without a state")
        val base = obj["base"] as? Map<*, *> ?: throw MalformedResponseException("PR #$number without a base")
        val head = obj["head"] as? Map<*, *> ?: throw MalformedResponseException("PR #$number without a head")
        val sha = head.string("sha")?.takeIf { SHA.matches(it) } ?: throw MalformedResponseException("PR #$number without a head SHA")
        val baseRef = base.string("ref")?.takeIf { it.isNotEmpty() } ?: throw MalformedResponseException("PR #$number without a base ref")
        return PullRequest(
            ref = PullRef(repo, number),
            title = obj.string("title").orEmpty(),
            isOpen = state == "open",
            isDraft = obj["draft"] == true,
            isMerged = obj["merged"] == true || obj.string("merged_at") != null,
            mergeable = obj["mergeable"] as? Boolean,
            body = obj.string("body").orEmpty(),
            baseRef = baseRef,
            headRef = head.string("ref").orEmpty(),
            headSha = sha,
            defaultBranch = (base["repo"] as? Map<*, *>)?.string("default_branch")?.takeIf { it.isNotEmpty() },
            labels = obj.list("labels").orEmpty().mapNotNull { (it as? Map<*, *>)?.string("name") }.toSet(),
        )
    }

    private fun instant(text: String): Instant? = try {
        Instant.parse(text)
    } catch (e: DateTimeParseException) {
        null
    }

    private fun parse(body: String): Any? = try {
        JsonParser.parse(body.removePrefix("﻿"))
    } catch (e: JsonParseException) {
        throw MalformedResponseException("Response is not valid JSON", e)
    }
}

/** One page of workflow runs, and GitHub's total across all pages (null when not sent). */
data class WorkflowRunPage(val runs: List<WorkflowRun>, val totalCount: Long?)
