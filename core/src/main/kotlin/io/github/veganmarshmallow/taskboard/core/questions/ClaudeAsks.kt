package io.github.veganmarshmallow.taskboard.core.questions

import io.github.veganmarshmallow.taskboard.core.preview.ClaudeMessage

/**
 * Whether a Claude comment asks the owner something (task #82): it has a "What I need from you"
 * part, or one of its lines ends with a question mark. Pure.
 */
object ClaudeAsks {
    private val QUESTION_END = Regex("""[?？؟]["'”’)\]*_`]*$""")

    fun asks(message: ClaudeMessage?): Boolean {
        message ?: return false
        if (message.needFromYou != null) return true
        return message.body.lineSequence().any { QUESTION_END.containsMatchIn(it.trimEnd()) }
    }
}
