package io.github.veganmarshmallow.taskboard.core.task

/**
 * Invisible formatting characters (Unicode category Cf): bidi marks such as U+200F RIGHT-TO-LEFT
 * MARK, bidi embeddings and isolates, zero-width space/joiners and the BOM. Hebrew keyboards and
 * copy-paste from RTL text often add them unseen, so text that holds only these is treated as
 * empty, and identifiers (repo names) drop them before parsing.
 */
object InvisibleText {
    fun isInvisible(c: Char): Boolean = Character.getType(c) == Character.FORMAT.toInt()

    fun strip(text: String): String = text.filterNot(::isInvisible)

    /** True when nothing visible is left: only whitespace and invisible formatting characters. */
    fun isBlank(text: String): Boolean = text.all { it.isWhitespace() || isInvisible(it) }
}
