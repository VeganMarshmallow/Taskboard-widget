package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.board.BoardItem
import io.github.veganmarshmallow.taskboard.core.board.MergeWait
import io.github.veganmarshmallow.taskboard.theme.DayNightColor
import io.github.veganmarshmallow.taskboard.theme.WidgetPalette

/**
 * A row's Merge button and its reason (#45): the PR to merge next as the theme's filled button (#46), every
 * other one muted with a short reason beside it. A row without a lane looks as it did before #45. A muted tap says which PR to merge first instead
 * of merging; with no PR to merge first it runs the usual checks, whose dialog explains the refusal.
 */
class MergeLaneViews(private val context: Context) {
    private val colors = DayNightColor(context)

    fun bind(views: RemoteViews, item: BoardItem, palette: WidgetPalette = WidgetPalette.CALM) {
        if (!item.canMerge) {
            views.setViewVisibility(R.id.item_merge, View.GONE)
            views.setViewVisibility(R.id.item_merge_note, View.GONE)
            return
        }
        val lane = item.mergeLane
        // No lane (a board saved before #45, or not ranked yet): the plain Merge button of #30, no reason.
        val strong = lane == null || lane.isNext
        views.setViewVisibility(R.id.item_merge, View.VISIBLE)
        views.setInt(R.id.item_merge, "setBackgroundResource", if (strong) palette.mergeBackground else palette.mergeMutedBackground)
        colors.setTextColor(views, R.id.item_merge, if (strong) palette.mergeText else palette.mergeMutedText)
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
            MergeWait.CHECKS_APPROVAL -> R.string.merge_wait_checks_approval
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
