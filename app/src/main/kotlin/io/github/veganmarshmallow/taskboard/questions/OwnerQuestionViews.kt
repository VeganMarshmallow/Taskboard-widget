package io.github.veganmarshmallow.taskboard.questions

import android.view.View
import android.widget.TextView
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.questions.OwnerQuestion
import io.github.veganmarshmallow.taskboard.preview.ClaudeCommentViews

/** One card of the owner's questions to Claude (task #82): the question, then Claude's answer or the wait. */
object OwnerQuestionViews {
    fun bind(card: View, question: OwnerQuestion) {
        val context = card.context
        card.findViewById<TextView>(R.id.question_task).text =
            if (question.general) context.getString(R.string.questions_general_question, question.issue)
            else context.getString(R.string.questions_task, question.issue, question.title)
        card.findViewById<TextView>(R.id.question_text).text = question.question
        card.findViewById<View>(R.id.question_answer_label).visibility = if (question.answered) View.VISIBLE else View.GONE
        card.findViewById<View>(R.id.question_waiting).visibility = if (question.answered) View.GONE else View.VISIBLE
        ClaudeCommentViews(card).bind(question.answer)
    }
}
