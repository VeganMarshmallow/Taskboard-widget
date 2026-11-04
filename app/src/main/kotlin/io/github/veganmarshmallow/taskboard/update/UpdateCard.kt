package io.github.veganmarshmallow.taskboard.update

import android.view.View
import android.widget.Button
import android.widget.TextView
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.update.UpdatePresenter
import io.github.veganmarshmallow.taskboard.theme.AppTheme

/** Binds an [UpdatePresenter.Model] to the views of `section_update.xml`. No logic beyond mapping. */
class UpdateCard(root: View) {
    private val context = root.context
    val status: TextView = root.findViewById(R.id.update_status)
    val checkButton: Button = root.findViewById(R.id.update_check)
    val updateButton: Button = root.findViewById(R.id.update_install)

    fun bind(model: UpdatePresenter.Model) {
        status.text = model.status
        status.setTextColor(if (model.isError) context.getColor(R.color.error) else AppTheme.color(context, R.attr.tbText))
        updateButton.visibility = if (model.showUpdate) View.VISIBLE else View.GONE
        updateButton.isEnabled = !model.busy
        checkButton.isEnabled = !model.busy
    }
}
