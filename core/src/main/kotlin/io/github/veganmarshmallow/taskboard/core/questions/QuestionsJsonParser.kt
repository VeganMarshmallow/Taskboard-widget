package io.github.veganmarshmallow.taskboard.core.questions

import io.github.veganmarshmallow.taskboard.core.github.MalformedResponseException
import io.github.veganmarshmallow.taskboard.core.json.string
import io.github.veganmarshmallow.taskboard.core.preview.IssueComment
import io.github.veganmarshmallow.taskboard.core.preview.IssueDetails
import io.github.veganmarshmallow.taskboard.core.preview.PreviewJsonParser

/** One comment from the repo-wide comments listing, with the issue it belongs to. */
data class TaskComment(val issue: Int, val comment: IssueComment)

/** Parses the listings the Questions screen reads (task #82). Throws [MalformedResponseException] on a wrong shape. */
object QuestionsJsonParser {
    private val ISSUE_NUMBER = Regex("""/issues/(\d{1,9})$""")

    /** An issues listing, with each issue's body. */
    fun parseIssues(body: String): List<IssueDetails> = array(body, "issues").mapIndexed { index, element ->
        PreviewJsonParser.issueOf(element as? Map<*, *> ?: throw MalformedResponseException("Issue $index is not an object"), index)
    }

    /** The repo-wide comments listing. Comments without a body or a readable `issue_url` are skipped. */
    fun parseRepoComments(body: String): List<TaskComment> = array(body, "comments").mapIndexedNotNull { index, element ->
        val obj = element as? Map<*, *> ?: throw MalformedResponseException("Comment $index is not an object")
        val issue = obj.string("issue_url")?.let { ISSUE_NUMBER.find(it) }?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it > 0 } ?: return@mapIndexedNotNull null
        PreviewJsonParser.commentOf(obj)?.let { TaskComment(issue, it) }
    }

    private fun array(body: String, what: String): List<*> =
        PreviewJsonParser.parse(body) as? List<*> ?: throw MalformedResponseException("Expected a JSON array of $what")
}
