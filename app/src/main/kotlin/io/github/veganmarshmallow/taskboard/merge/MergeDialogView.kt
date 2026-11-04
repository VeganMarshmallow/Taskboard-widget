package io.github.veganmarshmallow.taskboard.merge

import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.merge.MergeButton
import io.github.veganmarshmallow.taskboard.core.merge.MergeDialog
import io.github.veganmarshmallow.taskboard.theme.AppTheme

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
        // #46: one accent. Merge and every other main action share the theme's filled button (dialog_merge.xml).
    }

    /** In a confirmation the first line is the PR title (prominent) and the second its repo and number (Merge all: its heading). */
    private fun bindLines(dialog: MergeDialog) {
        lines.removeAllViews()
        dialog.lines.forEachIndexed { index, text ->
            val lead = dialog.kind == MergeDialog.Kind.CONFIRM && index == 0
            val refusalLead = dialog.kind == MergeDialog.Kind.REFUSAL && index == 0
            lines.addView(TextView(context).apply {
                this.text = text
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                textSize = if (lead) 16f else 14f
                setTextColor(AppTheme.color(context, if (lead || refusalLead) R.attr.tbText else R.attr.tbMuted))
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
        MergeButton.MERGE_ALL -> R.string.merge_all
        MergeButton.CANCEL -> R.string.merge_cancel
        MergeButton.CLOSE -> R.string.merge_close
        MergeButton.OPEN_GITHUB -> R.string.merge_open_github
        MergeButton.EDIT_TOKEN -> R.string.merge_edit_token
        MergeButton.SET_UP -> R.string.merge_set_up
    }
}
