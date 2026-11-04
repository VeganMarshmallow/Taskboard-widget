package io.github.veganmarshmallow.taskboard.core.board

/**
 * Where a tab label's count sits, so the app can weight the name and the count differently (#46:
 * the count must not outshout the name). The label's wording and order come from translations,
 * e.g. "Todo 3" or "3 Todo", so the count is found rather than assumed to be last.
 */
object TabLabel {
    /**
     * The index range of [count] inside [label]: its last occurrence, since a name may itself
     * contain digits ("Q3 3"). Null when the count is empty or missing from the label.
     */
    fun countRange(label: String, count: String): IntRange? {
        if (count.isEmpty()) return null
        val start = label.lastIndexOf(count)
        if (start < 0) return null
        return start until start + count.length
    }

    /** The name's range: everything before or after the count, without the space between them. */
    fun nameRange(label: String, count: String): IntRange? {
        val counted = countRange(label, count) ?: return label.trimmedRange(0, label.length)
        val before = label.trimmedRange(0, counted.first)
        val after = label.trimmedRange(counted.last + 1, label.length)
        return when {
            before != null && after != null -> if (before.count() >= after.count()) before else after
            else -> before ?: after
        }
    }

    private fun String.trimmedRange(from: Int, to: Int): IntRange? {
        var start = from
        var end = to
        while (start < end && this[start].isWhitespace()) start++
        while (end > start && this[end - 1].isWhitespace()) end--
        return if (start < end) start until end else null
    }
}
