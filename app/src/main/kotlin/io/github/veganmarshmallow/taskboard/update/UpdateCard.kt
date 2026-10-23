package io.github.veganmarshmallow.taskboard.update

import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.update.UpdatePresenter

/** Binds an [UpdatePresenter.Model] to the views of `section_update.xml`. No logic beyond mapping. */
class UpdateCard(root: View) {
    private val context = root.context
    val status: TextView = root.findViewById(R.id.update_status)
    val progress: ProgressBar = root.findViewById(R.id.update_progress)
    val checkButton: Button = root.findViewById(R.id.update_check)
    val updateButton: Button = root.findViewById(R.id.update_install)

    fun bind(model: UpdatePresenter.Model) {
        status.text = model.status
        status.setTextColor(context.getColor(if (model.isError) R.color.error else R.color.update_text))
        updateButton.visibility = if (model.showUpdate) View.VISIBLE else View.GONE
        updateButton.isEnabled = !model.busy
        checkButton.isEnabled = !model.busy
        when (val p = model.progressPercent) {
            null -> progress.visibility = View.GONE
            -1 -> { progress.visibility = View.VISIBLE; progress.isIndeterminate = true }
            else -> { progress.visibility = View.VISIBLE; progress.isIndeterminate = false; progress.progress = p }
        }
    }
}
