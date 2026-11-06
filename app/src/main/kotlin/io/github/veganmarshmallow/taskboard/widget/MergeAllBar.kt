package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.merge.MergeDialogs
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllBanner
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllText
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.theme.DayNightColor
import io.github.veganmarshmallow.taskboard.theme.WidgetPalette

/**
 * The widget's Merge all line (#57), under the tabs: "3 PRs ready to merge [Merge all]" on Needs you,
 * "Merging 2 of 5… waiting for checks [Cancel]" while a run goes on, and how it ended [OK] after.
 * What it says is [MergeAllBanner]'s; this only maps it onto the views and their taps.
 */
class MergeAllBar(private val context: Context) {
    private val colors = DayNightColor(context)
    private val text = MergeAllText(MergeDialogs(AppGraph.messages(context)))

    fun bind(views: RemoteViews, board: Board?, selected: Section, palette: WidgetPalette = WidgetPalette.CALM) {
        if (!palette.isLayoutDefault) recolour(views, palette)
        val store = AppGraph.mergeAllStore(context)
        val state = store.load()
        val cancelling = state != null && store.cancelRequested(state.runId)
        val banner = MergeAllBanner.of(state, cancelling, board, selected == Section.NEEDS_YOU, text)
        if (banner == null) {
            views.setViewVisibility(R.id.merge_all_bar, View.GONE)
            return
        }
        views.setViewVisibility(R.id.merge_all_bar, View.VISIBLE)
        views.setTextViewText(R.id.merge_all_text, banner.text)
        val button = banner.button
        views.setViewVisibility(R.id.merge_all_button, if (button == null) View.GONE else View.VISIBLE)
        button ?: return
        views.setTextViewText(R.id.merge_all_button, context.getString(label(button)))
        views.setOnClickPendingIntent(
            R.id.merge_all_button,
            when (button) {
                MergeAllBanner.Button.MERGE_ALL -> WidgetIntents.mergeAll(context)
                MergeAllBanner.Button.CANCEL -> WidgetIntents.cancelMergeAll(context)
                MergeAllBanner.Button.DISMISS -> WidgetIntents.dismissMergeAll(context)
            },
        )
    }

    /** Another theme's bar and button (#46); the layout carries the default look. */
    private fun recolour(views: RemoteViews, palette: WidgetPalette) {
        views.setInt(R.id.merge_all_bar, "setBackgroundResource", palette.mergeAllBackground)
        views.setInt(R.id.merge_all_button, "setBackgroundResource", palette.mergeBackground)
        colors.setTextColor(views, R.id.merge_all_button, palette.mergeText)
        colors.setTextColor(views, R.id.merge_all_text, palette.mergeMutedText)
    }

    private fun label(button: MergeAllBanner.Button): Int = when (button) {
        MergeAllBanner.Button.MERGE_ALL -> R.string.merge_all
        MergeAllBanner.Button.CANCEL -> R.string.merge_all_cancel
        MergeAllBanner.Button.DISMISS -> R.string.merge_all_dismiss
    }
}
