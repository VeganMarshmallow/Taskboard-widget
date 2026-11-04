package io.github.veganmarshmallow.taskboard.widget

import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Toast
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.merge.MergeFlow
import io.github.veganmarshmallow.taskboard.mergeall.MergeAllFlow
import io.github.veganmarshmallow.taskboard.preview.TaskPreviewActivity
import io.github.veganmarshmallow.taskboard.theme.AppTheme

/**
 * Invisible trampoline for list-row taps. Collection rows can only share one PendingIntent template,
 * and rows need several behaviours: previewing a task or opening a link (activities) and approving (a broadcast).
 * Approving asks for confirmation first, because widget mis-taps are easy and approval lets Claude start work.
 * Merging (task #30) runs the shared [MergeFlow]: its checks, then a confirmation with the PR's title.
 * Merge all (#57), from the widget's Merge all line, runs [MergeAllFlow].
 */
class RowActionActivity : Activity() {
    /** The merge dialog's flow, for a Merge tap; read by instrumented tests. */
    var mergeFlow: MergeFlow? = null
        private set

    /** The Merge all dialog's flow; read by instrumented tests. */
    var mergeAllFlow: MergeAllFlow? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        when (val action = RowAction.from(intent)) {
            is RowAction.Open -> open(action.url)
            is RowAction.Approve -> confirmApprove(action)
            is RowAction.Preview -> preview(action.issue)
            is RowAction.Merge -> action.mergeFirst?.let(::mergeFirst) ?: merge(action.issue)
            RowAction.MergeAll -> { mergeAllFlow = MergeAllFlow(this, onClosed = { finish() }).also { it.start() } }
            null -> finish()
        }
    }

    override fun onDestroy() {
        mergeFlow?.close()
        mergeAllFlow?.close()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MergeAllFlow.PERMISSION_REQUEST) mergeAllFlow?.onPermissionAsked()
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
