package io.github.veganmarshmallow.taskboard.core.markdown

/**
 * Turns Markdown blocks into plain text: [of] joins them into one line, [lines] keeps one line per block.
 *
 * [of] joins Markdown blocks into one line of plain text. Blocks that already end in punctuation are
 * joined with a space; otherwise a list item is followed by "; " and other blocks by ". ", so a
 * bullet list reads as a sentence. Rules and code blocks are skipped.
 *
 * Callers show one short line, so no further blocks are read once the output reaches [MAX_OUTPUT]
 * characters (each block is itself bounded by [MarkdownInline.strip]).
 */
object PlainText {
    const val MAX_OUTPUT = 2_000
    /** [lines] stops reading blocks past this many characters. */
    const val MAX_LINES_OUTPUT = 10_000
    const val BULLET = "• "
    private const val ENDS_SENTENCE = ".!?:;,…—–-׃"
    private const val CLOSERS = "\"')]}»”’"

    fun of(blocks: List<MarkdownBlock>): String {
        val out = StringBuilder()
        var previousWasItem = false
        for (block in blocks) {
            if (out.length >= MAX_OUTPUT) break
            val text = visibleText(block) ?: continue
            if (out.isNotEmpty()) out.append(separatorAfter(out, previousWasItem))
            out.append(text)
            previousWasItem = block is MarkdownBlock.ListItem
        }
        return out.toString()
    }

    fun of(markdown: String): String = of(MarkdownBlocks.parse(markdown))

    /**
     * One line per block, list items starting with [BULLET]; rules and code blocks are skipped.
     * No further blocks are read once the output reaches [maxLength] characters.
     */
    fun lines(blocks: List<MarkdownBlock>, maxLength: Int = MAX_LINES_OUTPUT): String {
        val out = StringBuilder()
        for (block in blocks) {
            if (out.length >= maxLength) break
            val text = visibleText(block) ?: continue
            if (out.isNotEmpty()) out.append('\n')
            if (block is MarkdownBlock.ListItem) out.append(BULLET)
            out.append(text)
        }
        return out.toString()
    }

    /** The block's text without inline Markdown, or null for rules, code and blocks with no text. */
    private fun visibleText(block: MarkdownBlock): String? = when (block) {
        is MarkdownBlock.Heading -> block.text
        is MarkdownBlock.Paragraph -> block.text
        is MarkdownBlock.ListItem -> block.text
        MarkdownBlock.Rule, MarkdownBlock.Code -> null
    }?.let(MarkdownInline::strip)?.takeIf { it.isNotEmpty() }

    private fun separatorAfter(text: CharSequence, wasItem: Boolean): String {
        val last = text.trimEnd { it in CLOSERS }.lastOrNull()
        return when {
            last == null || last in ENDS_SENTENCE -> " "
            wasItem -> "; "
            else -> ". "
        }
    }
}
