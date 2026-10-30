package io.github.veganmarshmallow.taskboard.core.task

import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.model.Labels
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Reads the responses of the task-creation calls. Never throws. */
object IssueResponseParser {
    /** The created issue from a `201 Created` body, compared against the [requested] labels. */
    fun created(body: String, requested: List<String>): CreatedIssue {
        val root = parse(body) as? Map<*, *> ?: return CreatedIssue(null)
        val number = when (val n = root["number"]) {
            is Long -> n.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
            is Double -> n.takeIf { it % 1.0 == 0.0 && it >= 1 && it <= Int.MAX_VALUE }?.toInt()
            else -> null
        }
        val labels = root["labels"] as? List<*> ?: return CreatedIssue(number)
        val applied = labels.mapNotNull { (it as? Map<*, *>)?.get("name") as? String ?: it as? String }
            .map(Labels::normalize).toSet()
        return CreatedIssue(number, requested.filter { Labels.normalize(it) !in applied })
    }

    /** Bodies of the issues (not pull requests) in one page of an issues listing, in order. Malformed input gives an empty list. */
    fun issueBodies(body: String): List<String> = (parse(body) as? List<*>).orEmpty()
        .filterIsInstance<Map<*, *>>()
        .filter { it["pull_request"] !is Map<*, *> }
        .mapNotNull { it["body"] as? String }

    /**
     * Number, title and creation time of each entry in one page of an issues listing, pull requests
     * flagged. Entries without a positive number or a title are skipped; malformed input gives an empty list.
     */
    fun recentIssues(body: String): List<RecentIssue> = (parse(body) as? List<*>).orEmpty()
        .filterIsInstance<Map<*, *>>()
        .mapNotNull { obj ->
            val number = (obj["number"] as? Long)?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: return@mapNotNull null
            val title = obj["title"] as? String ?: return@mapNotNull null
            RecentIssue(number, title, instant(obj["created_at"] as? String), obj["pull_request"] is Map<*, *>)
        }

    private fun instant(raw: String?): Instant? = try {
        raw?.let { OffsetDateTime.parse(it).toInstant() }
    } catch (e: DateTimeParseException) {
        null
    }

    /** GitHub's `message` from an error body, plus the first `errors[].message` or field, if any. */
    fun errorMessage(body: String): String? {
        val root = parse(body) as? Map<*, *> ?: return null
        val message = (root["message"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val first = (root["errors"] as? List<*>)?.firstOrNull()
        val detail = when (first) {
            is Map<*, *> -> (first["message"] as? String)
                ?: listOfNotNull(first["field"] as? String, first["code"] as? String).joinToString(" ").ifEmpty { null }
            is String -> first
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() }
        return listOfNotNull(message, detail).joinToString(": ").ifEmpty { null }?.take(MAX_MESSAGE)
    }

    private const val MAX_MESSAGE = 200

    private fun parse(body: String): Any? = try {
        JsonParser.parse(body.removePrefix("﻿"))
    } catch (e: Exception) {
        null
    }
}
