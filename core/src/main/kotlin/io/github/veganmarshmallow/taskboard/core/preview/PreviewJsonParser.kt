package io.github.veganmarshmallow.taskboard.core.preview

import io.github.veganmarshmallow.taskboard.core.github.IssueJsonParser
import io.github.veganmarshmallow.taskboard.core.github.MalformedResponseException
import io.github.veganmarshmallow.taskboard.core.json.JsonParseException
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.json.list
import io.github.veganmarshmallow.taskboard.core.json.long
import io.github.veganmarshmallow.taskboard.core.json.string
import io.github.veganmarshmallow.taskboard.core.model.Labels

/** Parses the single-issue and issue-comments responses. Throws [MalformedResponseException] on a wrong shape. */
object PreviewJsonParser {
    fun parseIssue(body: String): IssueDetails {
        val obj = parse(body) as? Map<*, *> ?: throw MalformedResponseException("Expected a JSON issue object")
        return issueOf(obj, 0)
    }

    /** One issue object of a single-issue response or of a listing (task #82 reads bodies from listings). */
    internal fun issueOf(obj: Map<*, *>, index: Int): IssueDetails =
        IssueDetails(IssueJsonParser.parseIssue(obj, index), obj.string("body").orEmpty(), labelColors(obj.list("labels")))

    /** Label objects' `name` → `color`. Plain-string labels get no colour; anything else is skipped. */
    private fun labelColors(raw: List<*>?): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        raw.orEmpty().forEach { label ->
            val (name, color) = when (label) {
                is Map<*, *> -> (label.string("name") ?: return@forEach) to label.string("color")
                is String -> label to null
                else -> return@forEach
            }
            val key = Labels.normalize(name)
            if (key.isNotEmpty() && out[key] == null) out[key] = color
        }
        return out
    }

    /** Comments without a usable body are skipped; a non-array or non-object element is an error. */
    fun parseComments(body: String): List<IssueComment> {
        val array = parse(body) as? List<*> ?: throw MalformedResponseException("Expected a JSON array of comments")
        return array.mapIndexedNotNull { index, element ->
            commentOf(element as? Map<*, *> ?: throw MalformedResponseException("Comment $index is not an object"))
        }
    }

    /** One comment object, or null without a usable body. */
    internal fun commentOf(obj: Map<*, *>): IssueComment? {
        val text = obj.string("body") ?: return null
        val createdAt = try {
            IssueJsonParser.parseInstant(obj.string("created_at"), 0)
        } catch (e: MalformedResponseException) {
            null // a bad timestamp only loses ordering information; list order still applies
        }
        return IssueComment(obj.long("id") ?: 0L, text, createdAt)
    }

    internal fun parse(body: String): Any? = try {
        JsonParser.parse(body.removePrefix("\uFEFF"))
    } catch (e: JsonParseException) {
        throw MalformedResponseException("Response is not valid JSON", e)
    }
}
