package io.github.veganmarshmallow.taskboard.preview

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.error.ErrorMessages
import io.github.veganmarshmallow.taskboard.core.preview.PreviewScreen
import io.github.veganmarshmallow.taskboard.core.reply.ReplyForm
import io.github.veganmarshmallow.taskboard.core.reply.ReplyResult
import io.github.veganmarshmallow.taskboard.core.reply.ReplyStep
import io.github.veganmarshmallow.taskboard.core.reply.ReplyText

/**
 * The preview's reply box (task #44): text, the "unblock the task" checkbox for tasks waiting on
 * the owner, the result line and Send. [bind] only maps state to views; [listen] wires the input.
 */
class ReplyViews(root: View, private val messages: ErrorMessages) {
    private val context = root.context
    private val container: View = root.findViewById(R.id.preview_reply)
    private val input: EditText = root.findViewById(R.id.preview_reply_input)
    private val unblock: CheckBox = root.findViewById(R.id.preview_reply_unblock)
    private val message: TextView = root.findViewById(R.id.preview_reply_message)
    private val send: Button = root.findViewById(R.id.preview_reply_send)

    fun bind(screen: PreviewScreen) {
        container.visibility = if (screen.showReply) View.VISIBLE else View.GONE
        // Send sits in the sheet's action row (#46 round 2), outside the box, so it follows the box itself.
        send.visibility = container.visibility
        val form = screen.reply
        if (input.text.toString() != form.draft) {
            input.setText(form.draft)
            input.setSelection(input.length())
        }
        input.isEnabled = !form.sending
        unblock.visibility = if (screen.offerUnblock && !form.retriesUnblock) View.VISIBLE else View.GONE
        if (unblock.isChecked != form.unblock) unblock.isChecked = form.unblock
        unblock.isEnabled = !form.sending
        bindMessage(form)
        send.isEnabled = form.canSend
        send.alpha = if (form.canSend || form.sending) 1f else DISABLED_ALPHA
        send.setText(
            when {
                form.sending -> R.string.reply_sending
                form.retriesUnblock -> R.string.reply_retry_unblock
                else -> R.string.reply_send
            },
        )
    }

    fun listen(onEdit: (String) -> Unit, onUnblock: (Boolean) -> Unit, onSend: () -> Unit) {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onEdit(s?.toString().orEmpty())
        })
        unblock.setOnCheckedChangeListener { _, checked -> onUnblock(checked) }
        send.setOnClickListener { onSend() }
    }

    private fun bindMessage(form: ReplyForm) {
        val failure = form.failure
        val sent = form.sent
        when {
            form.tooLong -> showMessage(context.getString(R.string.reply_too_long, ReplyText.MAX_LENGTH), error = true)
            failure != null -> showMessage(failureText(failure), error = true)
            sent != null -> showMessage(
                context.getString(if (sent.unblocked) R.string.reply_sent_unblocked else R.string.reply_sent),
                error = false,
            )
            else -> message.visibility = View.GONE
        }
    }

    private fun failureText(failure: ReplyResult.Failed): String {
        val reason = sentence(messages.message(failure.error))
        return when (failure.step) {
            ReplyStep.COMMENT -> context.getString(R.string.reply_failed, reason) +
                if (failure.mayHavePosted) " " + context.getString(R.string.reply_may_have_posted) else ""
            ReplyStep.UNBLOCK -> context.getString(R.string.reply_unblock_failed, reason)
        }
    }

    /** Error messages may or may not end in a full stop; the reply's text adds a sentence after them. */
    private fun sentence(text: String): String {
        val last = text.trimEnd().lastOrNull() ?: return text
        return if (last in SENTENCE_ENDS) text else "$text."
    }

    private fun showMessage(text: String, error: Boolean) {
        message.visibility = View.VISIBLE
        message.text = text
        message.setTextColor(context.getColor(if (error) R.color.widget_error_text else R.color.ok))
        if (error) message.setBackgroundResource(R.drawable.error_background) else message.background = null
    }

    private companion object {
        const val DISABLED_ALPHA = 0.45f
        val SENTENCE_ENDS = setOf('.', '!', '?', '…')
    }
}
