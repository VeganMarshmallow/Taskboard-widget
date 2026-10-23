package io.github.veganmarshmallow.taskboard.core.preview

import io.github.veganmarshmallow.taskboard.core.board.TitleCleaner
import io.github.veganmarshmallow.taskboard.core.markdown.MarkdownBlock
import io.github.veganmarshmallow.taskboard.core.markdown.MarkdownBlocks
import io.github.veganmarshmallow.taskboard.core.markdown.MarkdownSections
import io.github.veganmarshmallow.taskboard.core.markdown.PlainText
import io.github.veganmarshmallow.taskboard.core.markdown.TextCap

/**
 * A very short plain-text summary of a task's issue body, for 2–3 lines in the preview.
 *
 * 1. The "What should be done" section (the issue form's main field), if it has any text.
 * 2. Otherwise the start of the body, without headings, the Repo field, or anything after the
 *    first `---` (issue footers such as "Approved by the owner…").
 * 3. Otherwise null: the caller shows "No description".
 *
 * Markdown is stripped, whitespace collapsed, bidi controls and broken UTF-16 removed, and the
 * result is capped at [MAX_LENGTH] characters on a word boundary.
 */
object TaskSummary {
    const val MAX_LENGTH = 200
    const val MAIN_SECTION = "What should be done"

    /** Sections that hold metadata rather than a description. */
    private val METADATA_SECTIONS = setOf("Repo", "Repository")

    /** What GitHub issue forms write for an optional field left empty. */
    private val EMPTY_FIELD = Regex("""^_?no response_?$""", RegexOption.IGNORE_CASE)

    /** Bodies beyond this are cut before parsing; a summary never needs more. */
    private const val MAX_INPUT = 64 * 1024

    fun of(body: String?, maxLength: Int = MAX_LENGTH): String? {
        if (body.isNullOrBlank()) return null
        val blocks = MarkdownBlocks.parse(body.take(MAX_INPUT)).filterNot(::isEmptyField)
        val text = MarkdownSections.content(blocks, MAIN_SECTION)?.let(::toText)?.takeIf { it.isNotEmpty() }
            ?: toText(MarkdownSections.withoutSections(beforeFooter(blocks), METADATA_SECTIONS))
        return text.takeIf(::isVisible)?.let { TextCap.cap(it, maxLength) }
    }

    /** False for text made only of invisible format characters (zero-width spaces, joiners, marks). */
    internal fun isVisible(text: String): Boolean =
        text.any { !it.isWhitespace() && Character.getType(it) != Character.FORMAT.toInt() }

    private fun toText(blocks: List<MarkdownBlock>): String = TitleCleaner.sanitize(PlainText.of(blocks))

    private fun isEmptyField(block: MarkdownBlock): Boolean =
        block is MarkdownBlock.Paragraph && EMPTY_FIELD.matches(block.text.trim())

    /** Everything before the first `---` that follows some content (a leading `---` is not a footer). */
    private fun beforeFooter(blocks: List<MarkdownBlock>): List<MarkdownBlock> {
        val firstContent = blocks.indexOfFirst { it !is MarkdownBlock.Rule }
        if (firstContent < 0) return emptyList()
        val rest = blocks.subList(firstContent, blocks.size)
        val rule = rest.indexOfFirst { it is MarkdownBlock.Rule }
        return if (rule < 0) rest else rest.subList(0, rule)
    }
}
