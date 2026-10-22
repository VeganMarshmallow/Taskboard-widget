package io.github.veganmarshmallow.taskboard.core.markdown

/** Fenced code block delimiters (` ``` ` or `~~~`, at most 3 spaces of indentation), as CommonMark defines them. */
internal object CodeFence {
    private val OPENING = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")

    /** The fence marker this line opens a code block with, or null when it does not open one. */
    fun opening(line: String): String? {
        val m = OPENING.find(line) ?: return null
        val marker = m.groupValues[1]
        // A backtick fence's info string may not contain backticks (then it is inline code).
        return marker.takeIf { it[0] == '~' || '`' !in m.groupValues[2] }
    }

    /** Whether [line] closes a block opened with [open]: the same character, at least as long, nothing else. */
    fun closes(line: String, open: String): Boolean {
        val t = line.trim()
        return t.length >= open.length && t.all { it == open[0] } && line.length - line.trimStart().length <= 3
    }
}
