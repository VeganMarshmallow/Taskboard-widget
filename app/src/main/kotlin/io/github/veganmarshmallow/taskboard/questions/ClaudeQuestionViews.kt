package io.github.veganmarshmallow.taskboard.questions

import android.view.View
import android.widget.TextView
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.error.ErrorMessages
import io.github.veganmarshmallow.taskboard.core.questions.ClaudeQuestion
import io.github.veganmarshmallow.taskboard.core.reply.ReplyForm
import io.github.veganmarshmallow.taskboard.preview.ClaudeCommentViews
import io.github.veganmarshmallow.taskboard.preview.ReplyBox
import io.github.veganmarshmallow.taskboard.preview.ReplyViews

/**
 * One card of Claude's open questions (task #82): the task, Claude's comment as the task sheet
 * shows it ([ClaudeCommentViews]), and the sheet's own reply box ([ReplyViews]), with "This
 * answers it: unblock" for a task waiting on the owner. [question] is the card's question.
 */
class ClaudeQuestionViews(val card: View, messages: ErrorMessages, val question: ClaudeQuestion) {
    private val context = card.context
    private val comment = ClaudeCommentViews(card)
    val reply = ReplyViews(card, messages)
    private val open: View = card.findViewById(R.id.question_open)

    init {
        card.findViewById<TextView>(R.id.question_task).text = context.getString(R.string.questions_task, question.issue, question.title)
        card.findViewById<View>(R.id.question_no_text).visibility = if (question.message == null) View.VISIBLE else View.GONE
        comment.bind(question.message)
    }

    fun bind(form: ReplyForm) = reply.bind(ReplyBox(shown = true, form = form, offerUnblock = question.needsOwner))

    fun onOpen(action: () -> Unit) = open.setOnClickListener { action() }
}
