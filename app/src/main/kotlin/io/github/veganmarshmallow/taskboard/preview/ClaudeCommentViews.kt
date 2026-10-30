package io.github.veganmarshmallow.taskboard.preview

import android.view.View
import android.widget.TextView
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.preview.ClaudeMessage

/**
 * Binds Claude's latest comment (task #43): the highlighted "What I need from you" box first, when
 * the comment has one, then the rest of the comment, collapsed when long. Hidden when there is none.
 */
class ClaudeCommentViews(root: View) {
    private val container: View = root.findViewById(R.id.preview_claude)
    private val need: View = root.findViewById(R.id.preview_claude_need)
    private val needText: TextView = root.findViewById(R.id.preview_claude_need_text)
    private val body: CollapsibleText = root.findViewById(R.id.preview_claude_body)

    fun bind(message: ClaudeMessage?) {
        container.visibility = if (message == null) View.GONE else View.VISIBLE
        message ?: return
        need.visibility = if (message.needFromYou == null) View.GONE else View.VISIBLE
        needText.text = message.needFromYou.orEmpty()
        body.visibility = if (message.body.isEmpty()) View.GONE else View.VISIBLE
        body.setText(message.body)
    }
}
