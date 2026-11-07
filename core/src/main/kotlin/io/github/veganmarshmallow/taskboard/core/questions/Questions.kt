package io.github.veganmarshmallow.taskboard.core.questions

import io.github.veganmarshmallow.taskboard.core.preview.ClaudeMessage
import java.time.Instant

/**
 * An open question Claude asked the owner on task [issue] (task #82). [message] is Claude's latest
 * comment, split like the preview shows it; null when the task waits on the owner (`needs-owner`)
 * but its question was not among the comments read. [needsOwner] offers "This answers it: unblock".
 */
data class ClaudeQuestion(
    val issue: Int,
    val title: String,
    val message: ClaudeMessage?,
    val askedAt: Instant?,
    val needsOwner: Boolean,
)

/**
 * A question the owner asked Claude (task #82): a `**Question for Claude:**` comment on task
 * [issue], or a general question's own issue ([general]). [answer] is Claude's `**Answer:**` reply,
 * null while it waits for Claude's next run.
 */
data class OwnerQuestion(
    val issue: Int,
    val title: String,
    val question: String,
    val askedAt: Instant?,
    val answer: ClaudeMessage?,
    val general: Boolean,
) {
    val answered: Boolean get() = answer != null
}

/** Everything the Questions screen lists. */
data class Questions(val fromClaude: List<ClaudeQuestion>, val toClaude: List<OwnerQuestion>) {
    val waitingOnClaude: Int get() = toClaude.count { !it.answered }

    companion object {
        val NONE = Questions(emptyList(), emptyList())
    }
}
