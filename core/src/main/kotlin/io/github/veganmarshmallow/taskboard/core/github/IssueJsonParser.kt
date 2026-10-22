package io.github.veganmarshmallow.taskboard.core.github

import io.github.veganmarshmallow.taskboard.core.json.JsonParseException
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.json.list
import io.github.veganmarshmallow.taskboard.core.json.long
import io.github.veganmarshmallow.taskboard.core.json.string
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels
import java.time.Instant
import java.time.format.DateTimeParseException

/** Thrown when a response body is not the JSON shape we expect. */
class MalformedResponseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Parses one page of `GET /repos/{owner}/{repo}/issues` into [Issue]s. */
object IssueJsonParser {
    fun parsePage(body: String): List<Issue> {
        val root = try {
            JsonParser.parse(body)
        } catch (e: JsonParseException) {
            throw MalformedResponseException("Response is not valid JSON", e)
        }
        val array = root as? List<*> ?: throw MalformedResponseException("Expected a JSON array of issues")
        return array.mapIndexed { index, element ->
            val obj = element as? Map<*, *> ?: throw MalformedResponseException("Issue $index is not an object")
            parseIssue(obj, index)
        }
    }

    private fun parseIssue(obj: Map<*, *>, index: Int): Issue {
        val number = obj.long("number")?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
            ?: throw MalformedResponseException("Issue $index has no valid number")
        val state = obj.string("state") ?: throw MalformedResponseException("Issue #$number has no state")
        return Issue(
            number = number,
            title = obj.string("title") ?: "",
            labels = parseLabels(obj.list("labels")),
            isOpen = state.equals("open", ignoreCase = true),
            closedAt = parseInstant(obj.string("closed_at"), number),
            isPullRequest = obj["pull_request"] != null,
        )
    }

    /** Labels come as objects with a `name`; tolerate plain strings too, and skip anything else. */
    private fun parseLabels(raw: List<*>?): Set<String> = raw.orEmpty().mapNotNull { label ->
        when (label) {
            is Map<*, *> -> label.string("name")
            is String -> label
            else -> null
        }
    }.map(Labels::normalize).filter { it.isNotEmpty() }.toSet()

    private fun parseInstant(raw: String?, number: Int): Instant? = raw?.let {
        try {
            Instant.parse(it)
        } catch (e: DateTimeParseException) {
            throw MalformedResponseException("Issue #$number has an invalid closed_at", e)
        }
    }
}
