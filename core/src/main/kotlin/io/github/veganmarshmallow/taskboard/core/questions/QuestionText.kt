package io.github.veganmarshmallow.taskboard.core.questions

import io.github.veganmarshmallow.taskboard.core.board.TitleCleaner
import io.github.veganmarshmallow.taskboard.core.preview.ClaudeComments
import io.github.veganmarshmallow.taskboard.core.reply.ReplyText

/**
 * The wording the owner's questions and Claude's answers use on GitHub (task #82). The board rules
 * (claude-workspace CLAUDE.md, section 2) look for [ASK_PREFIX]; Claude starts its reply with
 * [ANSWER_PREFIX]. Pure.
 */
object QuestionText {
    const val ASK_PREFIX = "**Question for Claude:**"
    const val ANSWER_PREFIX = "**Answer:**"

    /** A general question's issue title: this, then the question's first line. */
    const val TITLE_PREFIX = "Question for Claude: "
    const val TITLE_MAX = 80

    /** The comment (or general question's issue body) for the owner's [text]. */
    fun comment(text: String): String = "$ASK_PREFIX\n\n${ReplyText.clean(text)}"

    /** The issue title of a general question: its first visible line, shortened. */
    fun issueTitle(text: String): String {
        val first = ReplyText.clean(text).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return TitleCleaner.clean(TITLE_PREFIX + first, TITLE_MAX)
    }

    fun isQuestion(body: String): Boolean = lead(body).startsWith(ASK_PREFIX, ignoreCase = true)

    fun isAnswer(body: String): Boolean = lead(body).startsWith(ANSWER_PREFIX, ignoreCase = true)

    /** The owner's question without its prefix, trimmed; [body] as is when it has no prefix. */
    fun questionText(body: String): String = ReplyText.clean(withoutPrefix(lead(body), ASK_PREFIX))

    /** Claude's answer without its prefix (the footer is dropped later, by [io.github.veganmarshmallow.taskboard.core.preview.ClaudeMessage]). */
    fun answerText(body: String): String = ReplyText.clean(withoutPrefix(lead(body), ANSWER_PREFIX))

    private fun lead(body: String) = ClaudeComments.withoutPrefix(body)

    private fun withoutPrefix(text: String, prefix: String) =
        if (text.startsWith(prefix, ignoreCase = true)) text.substring(prefix.length) else text
}
