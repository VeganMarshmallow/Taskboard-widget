package io.github.veganmarshmallow.taskboard.widget

import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Toast
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.preview.TaskPreviewActivity

/**
 * Invisible trampoline for list-row taps. Collection rows can only share one PendingIntent template,
 * and rows need several behaviours: previewing a task or opening a link (activities) and approving (a broadcast).
 * Approving asks for confirmation first, because widget mis-taps are easy and approval lets Claude start work.
 */
class RowActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (val action = RowAction.from(intent)) {
            is RowAction.Open -> open(action.url)
            is RowAction.Approve -> confirmApprove(action)
            is RowAction.Preview -> preview(action.issue)
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

    private fun preview(issue: Int) {
        startActivity(TaskPreviewActivity.intent(this, issue))
        finish()
    }

    private fun confirmApprove(action: RowAction.Approve) {
        ApproveDialog.show(
            this, action.issue, action.title,
            onConfirm = { sendBroadcast(WidgetIntents.approveIntent(this, action.issue)) },
            onDismiss = { finish() },
        )
    }
}
