package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.json.JsonWriter
import io.github.veganmarshmallow.taskboard.core.merge.PullRef
import io.github.veganmarshmallow.taskboard.core.merge.PullRequest
import io.github.veganmarshmallow.taskboard.core.model.Repo

val WIDGET = Repo("VeganMarshmallow", "Taskboard-widget")
const val API_REPOS = "https://api.github.com/repos"
const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

/** GitHub-shaped JSON for a pull request. */
fun pullJson(
    number: Int,
    body: String? = "Closes VeganMarshmallow/claude-workspace#30",
    title: String = "PR $number",
    state: String = "open",
    draft: Boolean = false,
    merged: Boolean = false,
    mergeable: Boolean? = true,
    base: String = "main",
    head: String = "claude/$number-branch",
    sha: String = SHA_A,
    defaultBranch: String? = "main",
): Map<String, Any?> = mapOf(
    "number" to number.toLong(),
    "title" to title,
    "state" to state,
    "draft" to draft,
    "merged" to merged,
    "mergeable" to mergeable,
    "body" to body,
    "base" to mapOf("ref" to base, "repo" to defaultBranch?.let { mapOf("default_branch" to it) }),
    "head" to mapOf("ref" to head, "sha" to sha),
    "html_url" to "https://evil.example/pull/$number",
)

fun pullsPage(vararg pulls: Map<String, Any?>): String = JsonWriter.write(pulls.toList())

private var nextRunId = 1000L

/**
 * One GitHub-shaped Actions workflow run. Each workflow name gets its own `workflow_id` unless
 * [workflow] is given, and every run gets a fresh `id` unless [id] is given.
 */
fun wfRun(
    name: String = "CI",
    conclusion: String? = "success",
    status: String = "completed",
    workflow: Long? = (name.hashCode().toLong() and 0xFFFF) + 1,
    number: Long? = 1,
    attempt: Long? = 1,
    createdAt: String? = "2026-10-30T10:00:00Z",
    sha: String? = SHA_A,
    id: Long? = nextRunId++,
): Map<String, Any?> = mapOf(
    "id" to id, "name" to name, "workflow_id" to workflow, "head_sha" to sha, "run_number" to number,
    "run_attempt" to attempt, "created_at" to createdAt, "status" to status, "conclusion" to conclusion,
    "html_url" to "https://evil.example/run",
)

/** `{"total_count": n, "workflow_runs": [...]}`; [total] defaults to the runs on this page. */
fun runsJson(vararg runs: Map<String, Any?>, total: Long? = runs.size.toLong()): String =
    JsonWriter.write(mapOf("total_count" to total, "workflow_runs" to runs.toList()))

fun passed(name: String = "CI") = wfRun(name)

fun commentsJson(vararg bodies: String): String = JsonWriter.write(
    bodies.mapIndexed { i, b -> mapOf("id" to (i + 1).toLong(), "body" to b, "created_at" to "2026-10-2${i % 10}T10:00:00Z") },
)

fun pr(
    number: Int,
    repo: Repo = WIDGET,
    open: Boolean = true,
    draft: Boolean = false,
    merged: Boolean = false,
    mergeable: Boolean? = true,
    base: String = "main",
    head: String = "claude/$number",
    defaultBranch: String? = "main",
    body: String = "",
    title: String = "PR $number",
) = PullRequest(PullRef(repo, number), title, open, draft, merged, mergeable, body, base, head, SHA_A, defaultBranch)
