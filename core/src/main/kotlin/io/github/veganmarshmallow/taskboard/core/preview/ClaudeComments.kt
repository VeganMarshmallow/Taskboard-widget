package io.github.veganmarshmallow.taskboard.core.preview

/**
 * Picks Claude's latest comment. Claude posts through the owner's account, so authorship can't
 * tell its comments apart; instead every Claude comment starts with 🤖 (started / progress),
 * ✅ (finished) or ⛔ (blocked). Leading whitespace, a BOM, zero-width characters and an emoji
 * variation selector are tolerated.
 */
object ClaudeComments {
    private val MARKERS = listOf("🤖", "✅", "⛔")
    /** Comments beyond this are cut before parsing; the preview never shows more. */
    private const val MAX_INPUT = 64 * 1024
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

    /** The latest Claude comment in full, as plain text split into its parts (task #43); null when there is none. */
    fun latestMessage(comments: List<IssueComment>): ClaudeMessage? =
        latest(comments)?.let { ClaudeMessage.of(withoutPrefix(it.body).take(MAX_INPUT)) }
}
