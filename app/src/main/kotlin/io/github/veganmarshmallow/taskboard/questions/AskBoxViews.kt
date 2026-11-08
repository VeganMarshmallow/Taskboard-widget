package io.github.veganmarshmallow.taskboard.questions

import android.view.View
import android.widget.TextView
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.error.ErrorMessages
import io.github.veganmarshmallow.taskboard.core.questions.QuestionsScreen
import io.github.veganmarshmallow.taskboard.preview.ReplyBox
import io.github.veganmarshmallow.taskboard.preview.ReplyViews
import io.github.veganmarshmallow.taskboard.preview.ReplyWords

/** The "Ask Claude" box (task #82): what it asks about, and a reply box worded as a question. */
class AskBoxViews(root: View, messages: ErrorMessages) {
    private val context = root.context
    private val topic: TextView = root.findViewById(R.id.questions_ask_topic)
    private val general: View = root.findViewById(R.id.questions_ask_general)
    val box = ReplyViews(root.findViewById(R.id.questions_ask), messages, ReplyWords.QUESTION)

    fun bind(screen: QuestionsScreen) {
        val about = screen.topic
        topic.text = when {
            about == null -> context.getString(R.string.questions_ask_general)
            about.title.isNullOrBlank() -> context.getString(R.string.questions_ask_about_number, about.number)
            else -> context.getString(R.string.questions_ask_about, about.number, about.title)
        }
        general.visibility = if (about != null && !screen.ask.sending) View.VISIBLE else View.GONE
        box.bind(ReplyBox(shown = true, form = screen.ask))
    }

    fun onGeneral(action: () -> Unit) = general.setOnClickListener { action() }
}
