package io.github.veganmarshmallow.taskboard.widget

import android.app.PendingIntent
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.board.TabSelection
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.filter.BoardFilter
import io.github.veganmarshmallow.taskboard.core.filter.filtered
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.theme.WidgetPalette

/**
 * Builds the widget's outer RemoteViews (header, tabs, error banner, list container) from a [WidgetState].
 * Pure view mapping: no AppWidgetManager, so it can be rendered directly in tests and screenshots.
 * The list rows come from [RowViews], put in by [ListBinding] (inside this update on Android 12+).
 */
class WidgetViews(private val context: Context) {
    private val time = TimeText(context)
    private val messages = AppGraph.messages(context)
    private val tabs = TabViews(context)
    private val list = ListBinding(context)
    private val mergeAll = MergeAllBar(context)
    private val filterBar = FilterBar(context)

    /**
     * [selected] is the tab to show; [WidgetRenderer] reads it from TabStore for each widget.
     * [updating]: a refresh is running right now (never true for a dead one; see RefreshTracker).
     * [widthDp]: the widget's width (null when unknown), which picks the row layout (#49).
     * [filter]: the board filter (#59). Tabs and rows show only matching tasks; Merge all still
     * counts every ready PR, since a run merges them all whatever the filter shows.
     */
    fun build(
        state: WidgetState,
        widgetId: Int,
        selected: Section = TabSelection.default(state.board),
        updating: Boolean = false,
        widthDp: Int? = null,
        filter: BoardFilter = BoardFilter.NONE,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_board)
        val shown = state.board?.filtered(filter)
        val palette = WidgetPalette.current(context)
        bindTheme(views, palette)
        bindHeader(views, state)
        if (updating) views.setTextViewText(R.id.updated, context.getString(R.string.updating))
        tabs.bind(views, shown, selected, widgetId, palette)
        filterBar.bind(views, filter)
        bindList(views, shown, state, selected, widgetId, widthDp, filter.isActive)
        bindError(views, state)
        mergeAll.bind(views, state.board, selected, palette)
        return views
    }

    /** The theme's card and title (#46). The layout is the default look, so only other themes add actions. */
    private fun bindTheme(views: RemoteViews, palette: WidgetPalette) {
        if (palette.isLayoutDefault) return
        views.setInt(ROOT, "setBackgroundResource", palette.background)
        views.setViewVisibility(R.id.title, if (palette.serifTitle) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.title_serif, if (palette.serifTitle) View.VISIBLE else View.GONE)
    }

    private fun bindHeader(views: RemoteViews, state: WidgetState) {
        views.setOnClickPendingIntent(R.id.refresh, WidgetIntents.refresh(context))
        views.setOnClickPendingIntent(R.id.new_task, WidgetIntents.newTask(context))
        // Task #16: taps outside the explicit controls do nothing. The title and "last updated" get no
        // intent of their own, and the root swallows stray taps with a no-op broadcast: some launchers
        // open the app (whose launcher screen is the token setup) when a tap finds no handler.
        // Buttons, list rows and the error banner have their own intents, which take precedence.
        views.setOnClickPendingIntent(ROOT, WidgetIntents.swallowTap(context))
        UpdateDot.bind(views, context)
        val fetched = state.board?.fetchedAt
        views.setTextViewText(
            R.id.updated,
            fetched?.let { context.getString(R.string.last_updated, time.format(it)) }
                ?: context.getString(R.string.never_updated),
        )
    }

    companion object {
        /** The layout's root, `@android:id/background` (also used by launchers for rounded corners). */
        const val ROOT = android.R.id.background
    }

    private fun bindList(views: RemoteViews, shown: Board?, state: WidgetState, selected: Section, widgetId: Int, widthDp: Int?, filtered: Boolean) {
        list.bind(views, shown, selected, widgetId, widthDp, filtered)
        views.setPendingIntentTemplate(R.id.board_list, WidgetIntents.rowTemplate(context))
        val hasBoard = state.board != null
        views.setViewVisibility(R.id.board_list, if (hasBoard) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.board_empty, if (hasBoard) View.GONE else View.VISIBLE)
        views.setTextViewText(
            R.id.board_empty,
            context.getString(if (state.error != null) R.string.no_data else R.string.loading),
        )
    }

    private fun bindError(views: RemoteViews, state: WidgetState) {
        val error = state.error
        if (error == null) {
            views.setViewVisibility(R.id.error, View.GONE)
            return
        }
        views.setViewVisibility(R.id.error, View.VISIBLE)
        views.setTextViewText(R.id.error, messages.widgetMessage(error))
        views.setOnClickPendingIntent(R.id.error, errorTap(error))
    }

    /** What tapping the error banner does: open setup for token problems, otherwise retry. The widget's only path to setup. */
    fun errorTap(error: BoardError): PendingIntent =
        if (messages.opensSetup(error)) WidgetIntents.setup(context) else WidgetIntents.refresh(context)
}
