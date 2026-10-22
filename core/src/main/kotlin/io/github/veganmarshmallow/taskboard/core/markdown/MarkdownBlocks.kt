package io.github.veganmarshmallow.taskboard.core.markdown

/**
 * A small, forgiving, line-based Markdown block scanner: enough of CommonMark to find sections and
 * produce readable plain text from GitHub issue and comment bodies. It never throws.
 *
 * Handles ATX and setext headings, paragraphs, bullet / numbered / task-list items with lazy
 * continuation lines, block quotes (markers dropped), fenced code (content dropped, an unclosed fence
 * runs to the end), thematic breaks, table rows (cells joined with ", ", separator rows dropped),
 * link reference definitions (dropped) and HTML comments (dropped, an unclosed one runs to the end).
 */
object MarkdownBlocks {
    private val ATX = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$""")
    private val RULE = Regex("""^ {0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*$""")
    private val SETEXT_H1 = Regex("""^ {0,3}=+[ \t]*$""")
    private val SETEXT_H2 = Regex("""^ {0,3}-+[ \t]*$""")
    private val FENCE = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")
    private val LIST_ITEM = Regex("""^[ \t]*(?:[-*+]|\d{1,9}[.)])(?:[ \t]+(.*))?$""")
    private val TASK_BOX = Regex("""^\[[ xX]][ \t]+""")
    private val QUOTE = Regex("""^[ \t]{0,3}>[ \t]?""")
    private val TABLE_SEPARATOR = Regex("""^[ \t]*\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)+\|?[ \t]*$|^[ \t]*\|[ \t]*:?-+:?[ \t]*\|[ \t]*$""")
    private val REFERENCE_DEFINITION = Regex("""^ {0,3}\[[^\]]+]:[ \t]*\S.*$""")
    private val HTML_COMMENT = Regex("""<!--.*?(?:-->|$)""", RegexOption.DOT_MATCHES_ALL)
    private val LINE_BREAK = Regex("""\r\n|\r|\n""")

    fun parse(markdown: String): List<MarkdownBlock> {
        val lines = HTML_COMMENT.replace(markdown, "").split(LINE_BREAK)
        return Scanner().apply { lines.forEach(::line) }.finish()
    }

    private class Scanner {
        private val blocks = ArrayList<MarkdownBlock>()
        private val current = StringBuilder()
        private var currentIsItem = false
        private var fence: String? = null

        fun line(raw: String) {
            fence?.let { open ->
                if (closesFence(raw, open)) fence = null
                return
            }
            FENCE.find(raw)?.let { m ->
                val marker = m.groupValues[1]
                // A backtick fence's info string may not contain backticks (then it is inline code).
                if (marker[0] == '~' || '`' !in m.groupValues[2]) {
                    flush()
                    blocks.add(MarkdownBlock.Code)
                    fence = marker
                    return
                }
            }
            var line = raw
            while (QUOTE.containsMatchIn(line)) line = QUOTE.replaceFirst(line, "")
            if (line.isBlank()) { flush(); return }
            if (current.isNotEmpty() && !currentIsItem) {
                if (SETEXT_H1.matches(line)) { heading(1); return }
                if (SETEXT_H2.matches(line)) { heading(2); return }
            }
            when {
                RULE.matches(line) -> { flush(); blocks.add(MarkdownBlock.Rule) }
                ATX.matches(line) -> {
                    flush()
                    val m = ATX.find(line)!!
                    blocks.add(MarkdownBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim()))
                }
                TABLE_SEPARATOR.matches(line) || REFERENCE_DEFINITION.matches(line) -> Unit
                LIST_ITEM.matches(line) -> {
                    flush()
                    val text = LIST_ITEM.find(line)!!.groupValues[1]
                    current.append(TASK_BOX.replaceFirst(text.trim(), ""))
                    currentIsItem = true
                }
                else -> append(tableRow(line) ?: line.trim())
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

        private fun closesFence(line: String, open: String): Boolean {
            val t = line.trim()
            return t.length >= open.length && t.all { it == open[0] } && line.length - line.trimStart().length <= 3
        }

        /** `| a | b |` → `a, b`; null when the line is not a table row. */
        private fun tableRow(line: String): String? {
            val t = line.trim()
            if (!t.startsWith("|") || t.length < 2) return null
            return t.trim('|').split('|').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        }
    }
}
