package io.github.veganmarshmallow.taskboard.core.board

/**
 * Makes titles safe for a single-line widget row: collapses whitespace and control characters,
 * and caps very long titles (the layout ellipsizes visually; the cap keeps RemoteViews small)
 * without splitting a surrogate pair, so emoji are never cut in half. Bidi embedding, override and
 * isolate controls are dropped: an unterminated one would reorder whatever text follows the title
 * (for example the rest of the Approve confirmation). Plain RTL text such as Hebrew is untouched.
 */
object TitleCleaner {
    const val MAX_LENGTH = 200
    const val UNTITLED = "(untitled)"

    fun clean(raw: String, maxLength: Int = MAX_LENGTH): String {
        require(maxLength >= 1) { "maxLength must be positive" }
        val collapsed = replaceInvalidUtf16(raw.filterNot(::isBidiControl))
            .map { if (it.isWhitespace() || it.isISOControl()) ' ' else it }
            .joinToString("")
            .trim()
            .replace(Regex(" {2,}"), " ")
        if (collapsed.isEmpty()) return UNTITLED
        if (collapsed.length <= maxLength) return collapsed
        var end = maxLength - 1
        if (end > 0 && Character.isHighSurrogate(collapsed[end - 1])) end--
        // A cut inside an emoji ZWJ sequence would leave a dangling joiner before the ellipsis.
        return collapsed.substring(0, end).trimEnd().trimEnd('\u200D').trimEnd() + "…"
    }

    /**
     * Unpaired surrogates and the noncharacters U+FFFE/U+FFFF become U+FFFD. They can arrive via
     * JSON escapes, and are not valid in the XML SharedPreferences file the cached board is saved to.
     */
    private fun replaceInvalidUtf16(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val pairs = Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])
            when {
                pairs -> { sb.append(c).append(s[i + 1]); i += 2; continue }
                Character.isSurrogate(c) || c == '\uFFFE' || c == '\uFFFF' -> sb.append(REPLACEMENT)
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private const val REPLACEMENT = '\uFFFD'

    /** U+202A..U+202E (LRE, RLE, PDF, LRO, RLO) and U+2066..U+2069 (LRI, RLI, FSI, PDI). */
    private fun isBidiControl(c: Char): Boolean = c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069'

}
