package io.github.veganmarshmallow.taskboard.core.reply

import io.github.veganmarshmallow.taskboard.core.model.Labels

/**
 * A label change a reply can make after posting: both put the task back in `status: todo` for the
 * next run, and each removes the labels that kept it out of Todo.
 *
 * - [UNBLOCK] (task #44): Claude's question is answered.
 * - [REQUEST_CHANGES] (task #65): the owner sends an in-review task back to work on its open PR.
 */
enum class StatusMove(val removes: List<String>) {
    UNBLOCK(listOf(Labels.NEEDS_OWNER, Labels.BLOCKED)),
    REQUEST_CHANGES(listOf(Labels.IN_REVIEW));

    /** The step a failed label change reports, so the form retries exactly this move. */
    val step: ReplyStep
        get() = when (this) {
            UNBLOCK -> ReplyStep.UNBLOCK
            REQUEST_CHANGES -> ReplyStep.REQUEST_CHANGES
        }
}
