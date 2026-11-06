package io.github.veganmarshmallow.taskboard.core.reply

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.task.CreateErrors

/** What one tap on the preview's Send does (tasks #44, #65). */
sealed class ReplyAction {
    /**
     * Post [text] as a comment; then, if [move] is set, change the task's labels. With
     * [StatusMove.REQUEST_CHANGES] the comment is the owner's change request ([ChangeRequestText]).
     */
    data class Post(val text: String, val move: StatusMove? = null) : ReplyAction() {
        /** A plain reply, or one that unblocks the task (task #44). */
        constructor(text: String, unblock: Boolean) : this(text, if (unblock) StatusMove.UNBLOCK else null)
    }

    /**
     * Only the label change: the comment was already posted but [move] failed. Retrying it this
     * way never posts the comment a second time.
     */
    data class Move(val move: StatusMove) : ReplyAction()

    /** The step a crash or timeout during this action is reported as. */
    val step: ReplyStep get() = (this as? Move)?.move?.step ?: ReplyStep.COMMENT

    companion object {
        /** Retry of the unblock labels alone (task #44). */
        val Unblock: Move = Move(StatusMove.UNBLOCK)
    }
}

/** The steps of a reply, so a failure can say which one failed: the comment, or which label change. */
enum class ReplyStep {
    COMMENT, UNBLOCK, REQUEST_CHANGES;

    /** The label change this step is, or null for the comment. */
    val move: StatusMove?
        get() = when (this) {
            COMMENT -> null
            UNBLOCK -> StatusMove.UNBLOCK
            REQUEST_CHANGES -> StatusMove.REQUEST_CHANGES
        }
}

sealed class ReplyResult {
    /** The comment is on GitHub (only the label change may have failed): refresh, and never post it again. */
    val posted: Boolean get() = this !is Failed || step != ReplyStep.COMMENT

    /**
     * Everything asked for was done. [unblocked] is true when the task was unblocked too;
     * [changesRequested] when it was sent back to Todo with the owner's change request (task #65).
     */
    data class Sent(val unblocked: Boolean, val changesRequested: Boolean = false) : ReplyResult()

    data class Failed(val step: ReplyStep, val error: BoardError) : ReplyResult() {
        /**
         * The comment step failed in a way where GitHub may still have saved it (lost connection,
         * timeout, 5xx), so the owner should check the issue before sending again.
         */
        val mayHavePosted: Boolean get() = step == ReplyStep.COMMENT && CreateErrors.mayAlreadyExist(error)
    }
}
