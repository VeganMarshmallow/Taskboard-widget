package io.github.veganmarshmallow.taskboard.core.markdown

/** A code span `[start, end)` in the text, backticks included; [content] is what lies between them. */
internal class CodeSpan(val start: Int, val end: Int, val content: String)

/**
 * Finds code spans as CommonMark does: a run of N backticks opens a span that the next run of exactly
 * N backticks closes; a run with no such partner is plain text. Linear: each run's partner is found
 * with one right-to-left pass, where the old `(`+)(.+?)\1` regex took seconds on a long backtick run.
 * A backslash escapes the first backtick of a run that would open a span (`\`not code\``), but not
 * one that closes it: backslashes are literal inside code.
 */
internal object CodeSpans {
    fun find(text: String): List<CodeSpan> {
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        val escaped = ArrayList<Boolean>()
        var i = 0
        while (i < text.length) {
            if (text[i] != '`') {
                i++
                continue
            }
            var end = i + 1
            while (end < text.length && text[end] == '`') end++
            starts.add(i)
            ends.add(end)
            escaped.add(backslashesBefore(text, i) % 2 == 1)
            i = end
        }
        val partner = IntArray(starts.size)
        val nextOfLength = HashMap<Int, Int>()
        for (r in starts.indices.reversed()) {
            val length = ends[r] - starts[r]
            val openingLength = if (escaped[r]) length - 1 else length
            partner[r] = if (openingLength > 0) nextOfLength[openingLength] ?: -1 else -1
            nextOfLength[length] = r
        }
        val spans = ArrayList<CodeSpan>()
        var r = 0
        while (r < starts.size) {
            val p = partner[r]
            if (p < 0) {
                r++
                continue
            }
            val start = if (escaped[r]) starts[r] + 1 else starts[r]
            spans.add(CodeSpan(start, ends[p], text.substring(ends[r], starts[p])))
            r = p + 1
        }
        return spans
    }

    /** Each backslash is counted for at most the one run right after it, so this stays linear. */
    private fun backslashesBefore(text: String, index: Int): Int {
        var i = index
        while (i > 0 && text[i - 1] == '\\') i--
        return index - i
    }
}
