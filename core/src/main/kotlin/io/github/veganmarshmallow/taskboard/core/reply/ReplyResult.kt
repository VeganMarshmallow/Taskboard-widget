package io.github.veganmarshmallow.taskboard.core.reply

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.task.CreateErrors

/** What one tap on the preview's Send does (task #44). */
sealed class ReplyAction {
    /** Post [text] as a comment; then, if [unblock], move the task from blocked/needs-owner back to todo. */
    data class Post(val text: String, val unblock: Boolean) : ReplyAction()

    /**
     * Only the label change: the reply was already posted but unblocking failed. Retrying it this
     * way never posts the comment a second time.
     */
    data object Unblock : ReplyAction()
}

/** The steps of a reply, so a failure can say which one failed. */
enum class ReplyStep { COMMENT, UNBLOCK }

sealed class ReplyResult {
    /** Everything asked for was done. [unblocked] is true when the labels were changed too. */
    data class Sent(val unblocked: Boolean) : ReplyResult()

    data class Failed(val step: ReplyStep, val error: BoardError) : ReplyResult() {
        /**
         * The comment step failed in a way where GitHub may still have saved it (lost connection,
         * timeout, 5xx), so the owner should check the issue before sending again.
         */
        val mayHavePosted: Boolean get() = step == ReplyStep.COMMENT && CreateErrors.mayAlreadyExist(error)
    }
}
