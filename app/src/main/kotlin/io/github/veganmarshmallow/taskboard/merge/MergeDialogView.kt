package io.github.veganmarshmallow.taskboard.merge

import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.merge.MergeButton
import io.github.veganmarshmallow.taskboard.core.merge.MergeDialog

/**
 * Binds a [MergeDialog] onto the inflated `dialog_merge` layout. Pure view mapping plus button
 * callbacks, so screenshots can bind any step directly.
 */
class MergeDialogView(val root: View, private val onButton: (MergeButton) -> Unit) {
    private val context = root.context
    private val title: TextView = root.findViewById(R.id.merge_title)
    private val progress: View = root.findViewById(R.id.merge_progress)
    private val lines: LinearLayout = root.findViewById(R.id.merge_lines)
    private val primary: Button = root.findViewById(R.id.merge_primary)
    private val secondary: Button = root.findViewById(R.id.merge_secondary)

    fun bind(dialog: MergeDialog) {
        title.text = dialog.title
        progress.visibility = if (dialog.busy) View.VISIBLE else View.GONE
        bindLines(dialog)
        bindButton(primary, dialog.primary)
        bindButton(secondary, dialog.secondary)
        primary.setBackgroundResource(if (dialog.primary == MergeButton.MERGE) R.drawable.merge_button_background else R.drawable.preview_button_background)
        primary.setTextColor(context.getColor(if (dialog.primary == MergeButton.MERGE) R.color.status_text else R.color.widget_accent_text))
    }

    /** In a confirmation the first line is the PR title (prominent) and the second its repo and number. */
    private fun bindLines(dialog: MergeDialog) {
        lines.removeAllViews()
        dialog.lines.forEachIndexed { index, text ->
            val lead = dialog.kind == MergeDialog.Kind.CONFIRM && index == 0
            val refusalLead = dialog.kind == MergeDialog.Kind.REFUSAL && index == 0
            lines.addView(TextView(context).apply {
                this.text = text
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                textSize = if (lead) 16f else 14f
                setTextColor(context.getColor(if (lead || refusalLead) R.color.widget_text else R.color.widget_muted))
                if (lead) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setLineSpacing(2 * resources.displayMetrics.density, 1f)
                setPadding(0, if (index == 0) 0 else (6 * resources.displayMetrics.density).toInt(), 0, 0)
            })
        }
    }

    private fun bindButton(button: Button, which: MergeButton?) {
        button.visibility = if (which == null) View.GONE else View.VISIBLE
        which ?: return
        button.setText(label(which))
        button.setOnClickListener { onButton(which) }
    }

    private fun label(which: MergeButton): Int = when (which) {
        MergeButton.MERGE -> R.string.merge
        MergeButton.CANCEL -> R.string.merge_cancel
        MergeButton.CLOSE -> R.string.merge_close
        MergeButton.OPEN_GITHUB -> R.string.merge_open_github
        MergeButton.EDIT_TOKEN -> R.string.merge_edit_token
        MergeButton.SET_UP -> R.string.merge_set_up
    }
}
