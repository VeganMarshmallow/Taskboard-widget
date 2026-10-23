package io.github.veganmarshmallow.taskboard.core.preview

import io.github.veganmarshmallow.taskboard.core.github.IssueJsonParser
import io.github.veganmarshmallow.taskboard.core.github.MalformedResponseException
import io.github.veganmarshmallow.taskboard.core.json.JsonParseException
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.json.long
import io.github.veganmarshmallow.taskboard.core.json.string

/** Parses the single-issue and issue-comments responses. Throws [MalformedResponseException] on a wrong shape. */
object PreviewJsonParser {
    fun parseIssue(body: String): IssueDetails {
        val obj = parse(body) as? Map<*, *> ?: throw MalformedResponseException("Expected a JSON issue object")
        return IssueDetails(IssueJsonParser.parseIssue(obj, 0), obj.string("body").orEmpty())
    }

    /** Comments without a usable body are skipped; a non-array or non-object element is an error. */
    fun parseComments(body: String): List<IssueComment> {
        val array = parse(body) as? List<*> ?: throw MalformedResponseException("Expected a JSON array of comments")
        return array.mapIndexedNotNull { index, element ->
            val obj = element as? Map<*, *> ?: throw MalformedResponseException("Comment $index is not an object")
            val text = obj.string("body") ?: return@mapIndexedNotNull null
            val createdAt = try {
                IssueJsonParser.parseInstant(obj.string("created_at"), 0)
            } catch (e: MalformedResponseException) {
                null // a bad timestamp only loses ordering information; list order still applies
            }
            IssueComment(obj.long("id") ?: 0L, text, createdAt)
        }
    }

    private fun parse(body: String): Any? = try {
        JsonParser.parse(body.removePrefix("\uFEFF"))
    } catch (e: JsonParseException) {
        throw MalformedResponseException("Response is not valid JSON", e)
    }
}
