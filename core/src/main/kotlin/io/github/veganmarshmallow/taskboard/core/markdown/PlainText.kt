package io.github.veganmarshmallow.taskboard.core.markdown

/**
 * Joins Markdown blocks into one line of plain text. Blocks that already end in punctuation are
 * joined with a space; otherwise a list item is followed by "; " and other blocks by ". ", so a
 * bullet list reads as a sentence. Rules and code blocks are skipped.
 */
object PlainText {
    private const val ENDS_SENTENCE = ".!?:;,…—–-׃"
    private const val CLOSERS = "\"')]}»”’"

    fun of(blocks: List<MarkdownBlock>): String {
        val out = StringBuilder()
        var previousWasItem = false
        for (block in blocks) {
            val text = when (block) {
                is MarkdownBlock.Heading -> block.text
                is MarkdownBlock.Paragraph -> block.text
                is MarkdownBlock.ListItem -> block.text
                MarkdownBlock.Rule, MarkdownBlock.Code -> continue
            }.let(MarkdownInline::strip)
            if (text.isEmpty()) continue
            if (out.isNotEmpty()) out.append(separatorAfter(out, previousWasItem))
            out.append(text)
            previousWasItem = block is MarkdownBlock.ListItem
        }
        return out.toString()
    }

    fun of(markdown: String): String = of(MarkdownBlocks.parse(markdown))

    private fun separatorAfter(text: CharSequence, wasItem: Boolean): String {
        val last = text.trimEnd { it in CLOSERS }.lastOrNull()
        return when {
            last == null || last in ENDS_SENTENCE -> " "
            wasItem -> "; "
            else -> ". "
        }
    }
}
