package io.github.veganmarshmallow.taskboard.core.markdown

/** A maximal run of one emphasis marker character, `[start, end)` in the text. */
internal class DelimiterRun(
    val char: Char,
    val start: Int,
    val end: Int,
    val canOpen: Boolean,
    val canClose: Boolean,
) {
    /** Markers already matched from each side; an opener is used from its end, a closer from its start. */
    var removedAtStart = 0
    var removedAtEnd = 0
    val remaining: Int get() = end - start - removedAtStart - removedAtEnd
}

/**
 * Finds the emphasis delimiter runs in a text and whether each can open or close, using CommonMark's
 * left- and right-flanking rules. `_` and a single `*` never open or close inside a word
 * (`snake_case`, `2*3*4`); `**` may (`a**b**c`). `~` counts only as a run of exactly two (`~~`).
 * Runs that can do neither are left out: they are plain text.
 */
internal object DelimiterRuns {
    const val MARKERS = "*_~"

    /**
     * Unicode punctuation and symbols. Private-use characters count too: [MarkdownInline] stands one in
     * for each backslash-escaped punctuation mark (`*line*\_here`) and removes any real ones first.
     */
    private val PUNCTUATION_TYPES = setOf(
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
        Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL, Character.PRIVATE_USE,
    ).map { it.toInt() }.toSet()

    fun scan(text: String): List<DelimiterRun> {
        val runs = ArrayList<DelimiterRun>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c !in MARKERS) {
                i++
                continue
            }
            var end = i + 1
            while (end < text.length && text[end] == c) end++
            if (c != '~' || end - i == 2) classify(text, c, i, end)?.let(runs::add)
            i = end
        }
        return runs
    }

    private fun classify(text: String, c: Char, start: Int, end: Int): DelimiterRun? {
        val before = if (start == 0) null else text.codePointBefore(start)
        val after = if (end == text.length) null else text.codePointAt(end)
        val left = flanking(before, after)
        val right = flanking(after, before)
        val notInWords = c == '_' || (c == '*' && end - start == 1)
        val canOpen = left && (!notInWords || !right || isPunctuation(before))
        val canClose = right && (!notInWords || !left || isPunctuation(after))
        return if (canOpen || canClose) DelimiterRun(c, start, end, canOpen, canClose) else null
    }

    /**
     * Left-flanking when called as (before, after), right-flanking as (after, before): not followed
     * by whitespace, and either not followed by punctuation or preceded by whitespace or punctuation.
     */
    private fun flanking(behind: Int?, ahead: Int?): Boolean =
        !isSpace(ahead) && (!isPunctuation(ahead) || isSpace(behind) || isPunctuation(behind))

    /** The start and end of the text count as whitespace. */
    private fun isSpace(cp: Int?): Boolean = cp == null || Character.isWhitespace(cp) || Character.isSpaceChar(cp)

    private fun isPunctuation(cp: Int?): Boolean = cp != null && Character.getType(cp) in PUNCTUATION_TYPES
}
