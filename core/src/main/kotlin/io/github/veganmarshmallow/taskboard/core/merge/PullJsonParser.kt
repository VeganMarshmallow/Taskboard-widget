package io.github.veganmarshmallow.taskboard.core.merge

import io.github.veganmarshmallow.taskboard.core.github.MalformedResponseException
import io.github.veganmarshmallow.taskboard.core.json.JsonParseException
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.json.list
import io.github.veganmarshmallow.taskboard.core.json.long
import io.github.veganmarshmallow.taskboard.core.json.string
import io.github.veganmarshmallow.taskboard.core.model.Repo

/**
 * Parses the pulls, check-runs and merge responses. Throws [MalformedResponseException] when a
 * field merging depends on (number, state, head SHA, base ref) is missing or has the wrong type.
 * The PR's repo is the one that was asked, never read from the response.
 */
object PullJsonParser {
    private val SHA = Regex("^[0-9a-fA-F]{7,64}$")

    fun parsePull(body: String, repo: Repo): PullRequest =
        pull(parse(body) as? Map<*, *> ?: throw MalformedResponseException("Expected a JSON pull request"), repo)

    /** A list of PRs. An element that is not a usable PR is an error, like the issue lists. */
    fun parsePulls(body: String, repo: Repo): List<PullRequest> {
        val array = parse(body) as? List<*> ?: throw MalformedResponseException("Expected a JSON array of pull requests")
        return array.map { pull(it as? Map<*, *> ?: throw MalformedResponseException("PR is not an object"), repo) }
    }

    /** `{"total_count": n, "check_runs": [...]}`. Runs without a name are kept as "check". */
    fun parseCheckRuns(body: String): CheckRunPage {
        val obj = parse(body) as? Map<*, *> ?: throw MalformedResponseException("Expected a check-runs object")
        val runs = obj.list("check_runs") ?: throw MalformedResponseException("Missing check_runs")
        return CheckRunPage(
            runs.map { raw ->
                val run = raw as? Map<*, *> ?: throw MalformedResponseException("Check run is not an object")
                CheckRun(run.string("name") ?: "check", run.string("status") ?: "unknown", run.string("conclusion"))
            },
            obj.long("total_count")?.toInt(),
        )
    }

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
        )
    }

    private fun parse(body: String): Any? = try {
        JsonParser.parse(body.removePrefix("﻿"))
    } catch (e: JsonParseException) {
        throw MalformedResponseException("Response is not valid JSON", e)
    }
}

/** One page of check runs, and GitHub's total across all pages (null when not sent). */
data class CheckRunPage(val runs: List<CheckRun>, val totalCount: Int?)
