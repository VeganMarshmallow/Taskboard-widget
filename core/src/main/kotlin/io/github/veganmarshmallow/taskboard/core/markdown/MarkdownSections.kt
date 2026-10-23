package io.github.veganmarshmallow.taskboard.core.markdown

/**
 * Finds a section of a Markdown document by its heading, the way GitHub issue forms write them
 * (`### What should be done`). Matching ignores case, inline formatting, extra spaces and a trailing
 * `:`/`?`/`.`. A section runs until the next heading of the same or a higher level, or a thematic
 * break (issue bodies put a `---` before their footer).
 */
object MarkdownSections {
    private val TRAILING_PUNCTUATION = Regex("""[\s:?.]+$""")

    /** The blocks under the first heading named [title], or null when there is no such heading. */
    fun content(blocks: List<MarkdownBlock>, title: String): List<MarkdownBlock>? {
        val wanted = normalize(title)
        val start = blocks.indexOfFirst { it is MarkdownBlock.Heading && normalize(it.text) == wanted }
        if (start < 0) return null
        val level = (blocks[start] as MarkdownBlock.Heading).level
        val body = blocks.subList(start + 1, blocks.size)
        val end = body.indexOfFirst { it is MarkdownBlock.Rule || (it is MarkdownBlock.Heading && it.level <= level) }
        return if (end < 0) body.toList() else body.subList(0, end).toList()
    }

    /** All blocks except headings, and except the content of sections named in [excluded]. */
    fun withoutSections(blocks: List<MarkdownBlock>, excluded: Set<String>): List<MarkdownBlock> {
        val skip = excluded.map(::normalize).toSet()
        val out = ArrayList<MarkdownBlock>()
        var skipBelowLevel = 0 // 0: not skipping; else skip until a heading at or above this level
        for (block in blocks) {
            if (block is MarkdownBlock.Heading) {
                if (skipBelowLevel > 0 && block.level > skipBelowLevel) continue
                skipBelowLevel = if (normalize(block.text) in skip) block.level else 0
                continue
            }
            if (block is MarkdownBlock.Rule) skipBelowLevel = 0
            if (skipBelowLevel == 0) out.add(block)
        }
        return out
    }

    fun normalize(heading: String): String =
        TRAILING_PUNCTUATION.replace(MarkdownInline.strip(heading), "").lowercase()
}
