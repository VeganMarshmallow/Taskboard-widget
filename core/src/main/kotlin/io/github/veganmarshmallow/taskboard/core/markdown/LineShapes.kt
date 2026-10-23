package io.github.veganmarshmallow.taskboard.core.markdown

/**
 * Whole-line shapes that are checked character by character instead of with a repeated regex group:
 * Java's regex engine recurses once per repetition of such a group, so a single long line of
 * `- - - …` or `|-|-|…` would overflow the stack. All checks are linear and never throw.
 */
internal object LineShapes {
    private const val RULE_CHARS = "-*_"

    /** A thematic break: up to 3 spaces, then 3 or more of one of `-`, `*`, `_`, optionally spaced. */
    fun isRule(line: String): Boolean {
        val indent = line.length - line.trimStart(' ').length
        if (indent > 3) return false
        val body = line.trim(' ', '\t')
        val marker = body.firstOrNull()?.takeIf { it in RULE_CHARS } ?: return false
        var count = 0
        for (c in body) {
            when (c) {
                marker -> count++
                ' ', '\t' -> Unit
                else -> return false
            }
        }
        return count >= 3
    }

    /**
     * A table's delimiter row (`|---|:-:|`, `--|--`, `| --- |`): cells of `:?-+:?` separated by pipes,
     * with at least two cells, or one cell between a leading and a trailing pipe.
     */
    fun isTableSeparator(line: String): Boolean {
        var t = line.trim(' ', '\t')
        if (t.isEmpty() || t.any { it !in "|:- \t" } || '-' !in t) return false
        val leading = t.startsWith('|')
        if (leading) t = t.substring(1)
        val trailing = t.endsWith('|')
        if (trailing) t = t.substring(0, t.length - 1)
        val cells = t.split('|')
        if (cells.size < 2 && !(leading && trailing)) return false
        return cells.all { isDelimiterCell(it.trim(' ', '\t')) }
    }

    private fun isDelimiterCell(cell: String): Boolean {
        val dashes = cell.removePrefix(":").removeSuffix(":")
        return dashes.isNotEmpty() && dashes.all { it == '-' }
    }

    /** The line without its block-quote markers (`> `, `>> `, `> > `), in one pass however deeply nested. */
    fun withoutQuoteMarkers(line: String): String {
        var start = 0
        while (true) {
            var i = start
            while (i < line.length && i - start < 3 && (line[i] == ' ' || line[i] == '\t')) i++
            if (i >= line.length || line[i] != '>') return line.substring(start)
            i++
            if (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
            start = i
        }
    }
}
