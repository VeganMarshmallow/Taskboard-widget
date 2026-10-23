package io.github.veganmarshmallow.taskboard.core.markdown

/**
 * Caps text at a length with an ellipsis, preferring to cut at a word boundary when one falls in
 * the last 40% of the allowed length. Never splits a surrogate pair or leaves a dangling emoji joiner,
 * and drops separator punctuation right before the ellipsis.
 */
object TextCap {
    private const val ELLIPSIS = "…"
    private const val DANGLING = " \t,;:·-–—\u200D"

    fun cap(text: String, max: Int): String {
        require(max >= 2) { "max must be at least 2" }
        if (text.length <= max) return text
        var end = max - ELLIPSIS.length
        val space = text.lastIndexOf(' ', end)
        if (space >= max * 6 / 10) end = space
        if (Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end).trimEnd { it in DANGLING } + ELLIPSIS
    }
}
