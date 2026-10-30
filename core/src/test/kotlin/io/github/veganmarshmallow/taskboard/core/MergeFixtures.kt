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

fun checkRunsJson(vararg runs: Triple<String, String, String?>): String = JsonWriter.write(
    mapOf(
        "total_count" to runs.size.toLong(),
        "check_runs" to runs.map { (name, status, conclusion) -> mapOf("name" to name, "status" to status, "conclusion" to conclusion) },
    ),
)

fun passed(name: String = "build") = Triple(name, "completed", "success")

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
