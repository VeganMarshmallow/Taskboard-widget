package io.github.veganmarshmallow.taskboard.core.board

/**
 * Makes titles safe for a single-line widget row: collapses whitespace and control characters,
 * and caps very long titles (the layout ellipsizes visually; the cap keeps RemoteViews small)
 * without splitting a surrogate pair, so emoji are never cut in half.
 */
object TitleCleaner {
    const val MAX_LENGTH = 200
    const val UNTITLED = "(untitled)"

    fun clean(raw: String, maxLength: Int = MAX_LENGTH): String {
        require(maxLength >= 1) { "maxLength must be positive" }
        val collapsed = raw.map { if (it.isWhitespace() || it.isISOControl()) ' ' else it }
            .joinToString("")
            .trim()
            .replace(Regex(" {2,}"), " ")
        if (collapsed.isEmpty()) return UNTITLED
        if (collapsed.length <= maxLength) return collapsed
        var end = maxLength - 1
        if (end > 0 && Character.isHighSurrogate(collapsed[end - 1])) end--
        return collapsed.substring(0, end).trimEnd() + "…"
    }
}
