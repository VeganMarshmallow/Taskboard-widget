package io.github.veganmarshmallow.taskboard.core.reply

/** Rules for the reply's text, shared by the form (to enable Send) and the service (as a last guard). */
object ReplyText {
    /** GitHub refuses comment bodies longer than this (HTTP 422). */
    const val MAX_LENGTH = 65_536

    /** Leading and trailing whitespace is dropped; the text inside is posted exactly as typed. */
    fun clean(text: String): String = text.trim()

    fun isSendable(text: String): Boolean = problem(text) == null

    /** Why [text] can't be sent, or null. Safe to show. */
    fun problem(text: String): String? {
        val cleaned = clean(text)
        return when {
            cleaned.isEmpty() -> "the reply is empty"
            cleaned.length > MAX_LENGTH -> "the reply is too long (${cleaned.length} of $MAX_LENGTH characters)"
            else -> null
        }
    }
}
