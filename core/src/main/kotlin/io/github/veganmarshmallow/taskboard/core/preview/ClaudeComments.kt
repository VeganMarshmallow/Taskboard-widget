package io.github.veganmarshmallow.taskboard.core.preview

import io.github.veganmarshmallow.taskboard.core.board.TitleCleaner
import io.github.veganmarshmallow.taskboard.core.markdown.PlainText
import io.github.veganmarshmallow.taskboard.core.markdown.TextCap

/**
 * Picks Claude's latest comment. Claude posts through the owner's account, so authorship can't
 * tell its comments apart; instead every Claude comment starts with 🤖 (started / progress),
 * ✅ (finished) or ⛔ (blocked). Leading whitespace, a BOM, zero-width characters and an emoji
 * variation selector are tolerated.
 */
object ClaudeComments {
    const val MAX_LENGTH = 160
    private val MARKERS = listOf("🤖", "✅", "⛔")
    private const val IGNORABLE_PREFIX = "\uFEFF\u200B‌‍\u2060\u200E\u200F"

    fun isFromClaude(body: String): Boolean = MARKERS.any { withoutPrefix(body).startsWith(it) }

    private fun withoutPrefix(body: String) = body.trimStart { it.isWhitespace() || it in IGNORABLE_PREFIX }

    /**
     * The newest Claude comment: latest [IssueComment.createdAt], ties (and missing times) broken
     * by position, later wins. GitHub lists comments oldest first.
     */
    fun latest(comments: List<IssueComment>): IssueComment? = comments
        .withIndex()
        .filter { isFromClaude(it.value.body) }
        .maxWithOrNull(compareBy<IndexedValue<IssueComment>, java.time.Instant?>(nullsFirst()) { it.value.createdAt }.thenBy { it.index })
        ?.value

    /** The latest Claude comment as one line of plain text, or null when there is none. */
    fun latestLine(comments: List<IssueComment>, maxLength: Int = MAX_LENGTH): String? =
        latest(comments)?.let { TitleCleaner.sanitize(PlainText.of(withoutPrefix(it.body))) }
            ?.takeIf(TaskSummary::isVisible)
            ?.let { TextCap.cap(it, maxLength) }
}
