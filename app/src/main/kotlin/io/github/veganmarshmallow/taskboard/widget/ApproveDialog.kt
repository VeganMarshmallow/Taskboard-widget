package io.github.veganmarshmallow.taskboard.widget

import android.app.Activity
import android.app.AlertDialog
import io.github.veganmarshmallow.taskboard.R

/**
 * The one Approve confirmation, shared by the widget row and the task preview. Approving lets Claude
 * start work, and widget mis-taps are easy, so it always asks first.
 */
object ApproveDialog {
    fun show(activity: Activity, issue: Int, title: String, onConfirm: () -> Unit, onDismiss: () -> Unit = {}) {
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(activity.getString(R.string.approve_title, issue))
            .setMessage(activity.getString(R.string.approve_message, title))
            .setPositiveButton(R.string.approve) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { onDismiss() }
            .show()
    }
}
