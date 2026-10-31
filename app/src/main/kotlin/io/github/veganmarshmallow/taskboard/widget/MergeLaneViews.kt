package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.board.BoardItem
import io.github.veganmarshmallow.taskboard.core.board.MergeWait

/**
 * A row's Merge button and its reason (#45): the PR to merge next in the strong merge purple, every
 * other one muted with a short reason beside it. A muted tap says which PR to merge first instead
 * of merging; with no PR to merge first it runs the usual checks, whose dialog explains the refusal.
 */
class MergeLaneViews(private val context: Context) {
    fun bind(views: RemoteViews, item: BoardItem) {
        if (!item.canMerge) {
            views.setViewVisibility(R.id.item_merge, View.GONE)
            views.setViewVisibility(R.id.item_merge_note, View.GONE)
            return
        }
        val lane = item.mergeLane
        val strong = lane?.isNext == true
        views.setViewVisibility(R.id.item_merge, View.VISIBLE)
        views.setInt(R.id.item_merge, "setBackgroundResource", if (strong) R.drawable.merge_background else R.drawable.merge_background_muted)
        views.setTextColor(R.id.item_merge, context.getColor(if (strong) R.color.status_text else R.color.merge_muted_text))
        val reason = lane?.wait?.takeUnless { strong }?.let { context.getString(textOf(it)) }
        views.setTextViewText(R.id.item_merge_note, reason.orEmpty())
        views.setViewVisibility(R.id.item_merge_note, if (reason == null) View.GONE else View.VISIBLE)
        val first = lane?.firstTitle?.takeUnless { strong }
        views.setOnClickFillInIntent(R.id.item_merge, RowAction.Merge(item.number, first).toFillIn())
    }

    companion object {
        fun textOf(wait: MergeWait): Int = when (wait) {
            MergeWait.QUEUED -> R.string.merge_wait_queued
            MergeWait.DRAFT -> R.string.merge_wait_draft
            MergeWait.STACKED -> R.string.merge_wait_stacked
            MergeWait.CHECKS_RUNNING -> R.string.merge_wait_checks_running
            MergeWait.CHECKS_FAILED -> R.string.merge_wait_checks_failed
            MergeWait.NO_CHECKS -> R.string.merge_wait_no_checks
            MergeWait.CONFLICT -> R.string.merge_wait_conflict
            MergeWait.CONFLICT_CLAUDE_FIXES -> R.string.merge_wait_conflict_claude
            MergeWait.UPDATING -> R.string.merge_wait_updating
            MergeWait.NO_PR -> R.string.merge_wait_no_pr
            MergeWait.UNKNOWN -> R.string.merge_wait_unknown
        }

        /** The muted button's message: "Merge <title> first". */
        fun mergeFirst(context: Context, title: String): String = context.getString(R.string.merge_first, title)
    }
}
