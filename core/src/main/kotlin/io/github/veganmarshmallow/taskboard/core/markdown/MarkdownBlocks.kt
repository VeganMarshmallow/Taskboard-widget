package io.github.veganmarshmallow.taskboard.core.markdown

/**
 * A small, forgiving, line-based Markdown block scanner: enough of CommonMark to find sections and
 * produce readable plain text from GitHub issue and comment bodies. It never throws.
 *
 * Handles ATX and setext headings, paragraphs, bullet / numbered / task-list items with lazy
 * continuation lines, block quotes (markers dropped), fenced code (content dropped, an unclosed fence
 * runs to the end), thematic breaks, table rows (one paragraph per row, cells joined with ", ",
 * separator rows dropped), link reference definitions (dropped) and HTML comments (dropped outside
 * code, see [HtmlComments]). Line shapes that would need a repeated regex group are in [LineShapes].
 */
object MarkdownBlocks {
    private val ATX = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$""")
    private val SETEXT_H1 = Regex("""^ {0,3}=+[ \t]*$""")
    private val SETEXT_H2 = Regex("""^ {0,3}-+[ \t]*$""")
    private val LIST_ITEM = Regex("""^[ \t]*(?:[-*+]|\d{1,9}[.)])(?:[ \t]+(.*))?$""")
    private val TASK_BOX = Regex("""^\[[ xX]][ \t]+""")
    private val REFERENCE_DEFINITION = Regex("""^ {0,3}\[[^\]]+]:[ \t]*\S.*$""")
    private val LINE_BREAK = Regex("""\r\n|\r|\n""")

    fun parse(markdown: String): List<MarkdownBlock> {
        val lines = HtmlComments.strip(markdown.split(LINE_BREAK))
        return Scanner().apply { lines.forEach(::line) }.finish()
    }

    private class Scanner {
        private val blocks = ArrayList<MarkdownBlock>()
        private val current = StringBuilder()
        private var currentIsItem = false
        private var fence: String? = null

        fun line(raw: String) {
            fence?.let { open ->
                if (CodeFence.closes(raw, open)) fence = null
                return
            }
            CodeFence.opening(raw)?.let { marker ->
                flush()
                blocks.add(MarkdownBlock.Code)
                fence = marker
                return
            }
            val line = LineShapes.withoutQuoteMarkers(raw)
            if (line.isBlank()) { flush(); return }
            if (current.isNotEmpty() && !currentIsItem) {
                if (SETEXT_H1.matches(line)) { heading(1); return }
                if (SETEXT_H2.matches(line)) { heading(2); return }
            }
            when {
                LineShapes.isRule(line) -> { flush(); blocks.add(MarkdownBlock.Rule) }
                ATX.matches(line) -> {
                    flush()
                    val m = ATX.find(line)!!
                    blocks.add(MarkdownBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim()))
                }
                LineShapes.isTableSeparator(line) || REFERENCE_DEFINITION.matches(line) -> Unit
                LIST_ITEM.matches(line) -> {
                    flush()
                    val text = LIST_ITEM.find(line)!!.groupValues[1]
                    current.append(TASK_BOX.replaceFirst(text.trim(), ""))
                    currentIsItem = true
                }
                else -> tableRow(line)?.let(::row) ?: append(line.trim())
            }
        }

        fun finish(): List<MarkdownBlock> {
            flush()
            return blocks
        }

        private fun append(text: String) {
            if (current.isNotEmpty()) current.append(' ')
            current.append(text)
        }

        /** A table row is a paragraph of its own, so rows read as separate sentences. */
        private fun row(text: String) {
            flush()
            if (text.isNotEmpty()) blocks.add(MarkdownBlock.Paragraph(text))
        }

        private fun heading(level: Int) {
            blocks.add(MarkdownBlock.Heading(level, current.toString().trim()))
            current.clear()
        }

        private fun flush() {
            if (current.isNotBlank()) {
                val text = current.toString().trim()
                blocks.add(if (currentIsItem) MarkdownBlock.ListItem(text) else MarkdownBlock.Paragraph(text))
            }
            current.clear()
            currentIsItem = false
        }

        /** `| a | b |` → `a, b`; null when the line is not a table row. */
        private fun tableRow(line: String): String? {
            val t = line.trim()
            if (!t.startsWith("|") || t.length < 2) return null
            return t.trim('|').split('|').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        }
    }
}
