package io.github.veganmarshmallow.taskboard.preview

import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.TextView
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.error.ErrorMessages
import io.github.veganmarshmallow.taskboard.core.preview.PreviewError
import io.github.veganmarshmallow.taskboard.core.preview.PreviewScreen
import io.github.veganmarshmallow.taskboard.core.preview.PreviewStatus
import io.github.veganmarshmallow.taskboard.core.preview.SummaryState
import io.github.veganmarshmallow.taskboard.theme.AppTheme

/**
 * Binds a [PreviewScreen] onto the inflated `activity_task_preview` layout. Pure view mapping (no
 * I/O, no listeners), so tests and screenshots can bind any state directly.
 */
class PreviewViews(private val root: View, private val messages: ErrorMessages) {
    private val context = root.context
    private val status: TextView = root.findViewById(R.id.preview_status)
    private val number: TextView = root.findViewById(R.id.preview_number)
    private val progress: View = root.findViewById(R.id.preview_progress)
    private val title: TextView = root.findViewById(R.id.preview_title)
    private val tags: FlowLayout = root.findViewById(R.id.preview_tags)
    private val summary: TextView = root.findViewById(R.id.preview_summary)
    private val claude = ClaudeCommentViews(root)
    private val tintsStatus = TypedValue().let { context.theme.resolveAttribute(R.attr.tbTintsStatus, it, true) && it.data != 0 }

    /** The reply box (task #44); the activity wires its input through [ReplyViews.listen]. */
    val reply = ReplyViews(root, messages)
    private val error: TextView = root.findViewById(R.id.preview_error)
    private val approved: View = root.findViewById(R.id.preview_approved)
    private val approve: Button = root.findViewById(R.id.preview_approve)
    private val merged: View = root.findViewById(R.id.preview_merged)
    private val merge: Button = root.findViewById(R.id.preview_merge)
    private val retry: Button = root.findViewById(R.id.preview_retry)

    fun bind(screen: PreviewScreen) {
        val task = screen.task
        bindStatus(task?.status)
        number.text = context.getString(R.string.preview_number, screen.number)
        title.text = task?.title ?: context.getString(R.string.preview_task_fallback, screen.number)
        progress.visibility = if (screen.loading || screen.approving) View.VISIBLE else View.GONE
        TagPillViews.bind(tags, task?.tags.orEmpty())
        bindSummary(screen.summaryState)
        claude.bind(screen.claudeComment)
        reply.bind(screen)
        error.showText(screen.error?.let { errorText(it, screen.number) })
        approved.visibility = if (screen.justApproved) View.VISIBLE else View.GONE
        approve.visibility = if (screen.showApprove || screen.approving) View.VISIBLE else View.GONE
        approve.isEnabled = !screen.approving
        approve.text = context.getString(if (screen.approving) R.string.preview_approving else R.string.approve)
        merged.visibility = if (screen.justMerged) View.VISIBLE else View.GONE
        merge.visibility = if (screen.showMerge) View.VISIBLE else View.GONE
        bindRetry(screen.error)
    }

    /** Whether the error's action button should open the token setup instead of retrying. */
    fun opensSetup(screen: PreviewScreen): Boolean = screen.error?.let { messages.opensSetup(it.error) } == true

    private fun bindStatus(value: PreviewStatus?) {
        status.visibility = if (value == null) View.GONE else View.VISIBLE
        value ?: return
        status.text = value.text
        status.backgroundTintList = if (tintsStatus) ColorStateList.valueOf(context.getColor(PreviewStatusStyle.container(value))) else null
    }

    private fun bindSummary(state: SummaryState) {
        when (state) {
            is SummaryState.Text -> summary.showText(state.text, muted = false)
            SummaryState.NoDescription -> summary.showText(context.getString(R.string.preview_no_description), muted = true)
            SummaryState.NotLoaded -> summary.showText(context.getString(R.string.loading), muted = true)
            SummaryState.Unavailable -> summary.showText(null)
        }
    }

    /** Only a failed load offers Retry (or Set up); a failed approval is retried with Approve itself. */
    private fun bindRetry(previewError: PreviewError?) {
        if (previewError !is PreviewError.Load) {
            retry.visibility = View.GONE
            return
        }
        retry.visibility = View.VISIBLE
        retry.setText(if (messages.opensSetup(previewError.error)) R.string.preview_set_up else R.string.preview_retry)
    }

    private fun errorText(previewError: PreviewError, issue: Int): String = when (previewError) {
        is PreviewError.Load -> context.getString(R.string.preview_load_failed, messages.message(previewError.error))
        is PreviewError.Approve -> context.getString(R.string.approve_failed, issue, messages.message(previewError.error))
    }

    private fun TextView.showText(value: String?, muted: Boolean? = null) {
        visibility = if (value == null) View.GONE else View.VISIBLE
        text = value.orEmpty()
        muted?.let { setTextColor(AppTheme.color(context, if (it) R.attr.tbMuted else R.attr.tbText)) }
    }
}
