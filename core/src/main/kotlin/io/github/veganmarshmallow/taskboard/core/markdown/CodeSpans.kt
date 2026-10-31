package io.github.veganmarshmallow.taskboard.core.markdown

/** A code span `[start, end)` in the text, backticks included; [content] is what lies between them. */
internal class CodeSpan(val start: Int, val end: Int, val content: String)

/**
 * Finds code spans as CommonMark does: a run of N backticks opens a span that the next run of exactly
 * N backticks closes; a run with no such partner is plain text. Linear: each run's partner is found
 * with one right-to-left pass, where the old `(`+)(.+?)\1` regex took seconds on a long backtick run.
 */
internal object CodeSpans {
    fun find(text: String): List<CodeSpan> {
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
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
            i = end
        }
        val partner = IntArray(starts.size)
        val nextOfLength = HashMap<Int, Int>()
        for (r in starts.indices.reversed()) {
            val length = ends[r] - starts[r]
            partner[r] = nextOfLength[length] ?: -1
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
            spans.add(CodeSpan(starts[r], ends[p], text.substring(ends[r], starts[p])))
            r = p + 1
        }
        return spans
    }
}
