package io.github.veganmarshmallow.taskboard.core.markdown

/**
 * Removes HTML comments (`<!-- … -->`, possibly spanning lines; an unclosed one runs to the end) from
 * Markdown lines, but never inside fenced code or code spans: there `<!--` is just text, and treating
 * it as a comment would swallow the rest of the body. A line that lies wholly inside a comment is
 * dropped, so a multi-line comment does not split the paragraph around it. Never throws.
 */
internal object HtmlComments {
    private const val OPEN = "<!--"
    private const val CLOSE = "-->"

    fun strip(lines: List<String>): List<String> {
        val out = ArrayList<String>(lines.size)
        var fence: String? = null
        var inComment = false
        for (line in lines) {
            val open = fence
            if (open != null) {
                if (CodeFence.closes(line, open)) fence = null
                out.add(line)
                continue
            }
            if (!inComment) {
                fence = CodeFence.opening(line)
                if (fence != null) {
                    out.add(line)
                    continue
                }
            }
            val startedInComment = inComment
            val result = StringBuilder()
            inComment = stripLine(line, inComment, result)
            // A line entirely inside a comment disappears instead of becoming a paragraph break.
            if (startedInComment && inComment) continue
            out.add(result.toString())
        }
        return out
    }

    /** Appends [line] without comments to [out]; returns whether a comment is still open at its end. */
    private fun stripLine(line: String, startInComment: Boolean, out: StringBuilder): Boolean {
        var inComment = startInComment
        var i = 0
        while (i < line.length) {
            if (inComment) {
                val end = line.indexOf(CLOSE, i)
                if (end < 0) return true
                inComment = false
                i = end + CLOSE.length
                continue
            }
            val c = line[i]
            when {
                c == '`' -> i = copyCodeSpan(line, i, out)
                line.startsWith(OPEN, i) -> { inComment = true; i += OPEN.length }
                else -> { out.append(c); i++ }
            }
        }
        return inComment
    }

    /** Copies the code span (or the lone backtick run) starting at [start] verbatim; returns the index after it. */
    private fun copyCodeSpan(line: String, start: Int, out: StringBuilder): Int {
        var runEnd = start
        while (runEnd < line.length && line[runEnd] == '`') runEnd++
        val run = runEnd - start
        var j = runEnd
        while (j < line.length) {
            if (line[j] != '`') { j++; continue }
            var k = j
            while (k < line.length && line[k] == '`') k++
            if (k - j == run) {
                out.append(line, start, k)
                return k
            }
            j = k
        }
        out.append(line, start, runEnd) // no closing run on this line: the backticks are literal
        return runEnd
    }
}
