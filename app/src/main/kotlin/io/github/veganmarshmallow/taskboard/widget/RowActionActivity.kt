package io.github.veganmarshmallow.taskboard.widget

import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Toast
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.merge.MergeFlow
import io.github.veganmarshmallow.taskboard.preview.TaskPreviewActivity

/**
 * Invisible trampoline for list-row taps. Collection rows can only share one PendingIntent template,
 * and rows need several behaviours: previewing a task or opening a link (activities) and approving (a broadcast).
 * Approving asks for confirmation first, because widget mis-taps are easy and approval lets Claude start work.
 * Merging (task #30) runs the shared [MergeFlow]: its checks, then a confirmation with the PR's title.
 */
class RowActionActivity : Activity() {
    /** The merge dialog's flow, for a Merge tap; read by instrumented tests. */
    var mergeFlow: MergeFlow? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (val action = RowAction.from(intent)) {
            is RowAction.Open -> open(action.url)
            is RowAction.Approve -> confirmApprove(action)
            is RowAction.Preview -> preview(action.issue)
            is RowAction.Merge -> action.mergeFirst?.let(::mergeFirst) ?: merge(action.issue)
            null -> finish()
        }
    }

    override fun onDestroy() {
        mergeFlow?.close()
        super.onDestroy()
    }

    /** A muted Merge (#45): another PR goes first. The row and its preview still open the PR on GitHub. */
    private fun mergeFirst(title: String) {
        Toast.makeText(this, MergeLaneViews.mergeFirst(this, title), Toast.LENGTH_LONG).show()
        finish()
    }

    private fun merge(issue: Int) {
        mergeFlow = MergeFlow(this, issue, onClosed = { finish() }).also { it.start() }
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
