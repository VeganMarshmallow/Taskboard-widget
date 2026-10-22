package io.github.veganmarshmallow.taskboard.widget

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Toast
import io.github.veganmarshmallow.taskboard.R

/**
 * Invisible trampoline for list-row taps. Collection rows can only share one PendingIntent template,
 * and rows need two behaviours: opening an issue (an activity) and approving (a broadcast).
 * Approving asks for confirmation first, because widget mis-taps are easy and approval lets Claude start work.
 */
class RowActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (val action = RowAction.from(intent)) {
            is RowAction.Open -> open(action.url)
            is RowAction.Approve -> confirmApprove(action)
            null -> finish()
        }
    }

    private fun open(url: String) {
        try {
            startActivity(WidgetIntents.viewUrl(url))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun confirmApprove(action: RowAction.Approve) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.approve_title, action.issue))
            .setMessage(getString(R.string.approve_message, action.title))
            .setPositiveButton(R.string.approve) { _, _ ->
                sendBroadcast(WidgetIntents.approveIntent(this, action.issue))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { finish() }
            .show()
    }
}
