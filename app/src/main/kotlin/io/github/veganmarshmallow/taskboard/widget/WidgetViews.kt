package io.github.veganmarshmallow.taskboard.widget

import android.app.PendingIntent
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.data.AppGraph

/**
 * Builds the widget's outer RemoteViews (header, error banner, list container) from a [WidgetState].
 * Pure view mapping: no AppWidgetManager, so it can be rendered directly in tests and screenshots.
 * The list rows themselves come from [RowViews] via [BoardListFactory].
 */
class WidgetViews(private val context: Context) {
    private val time = TimeText(context)
    private val messages = AppGraph.messages(context)

    fun build(state: WidgetState, widgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_board)
        bindHeader(views, state)
        bindList(views, state, widgetId)
        bindError(views, state)
        return views
    }

    /** A partial update that only swaps the header's time for "updating…". */
    fun updating(): RemoteViews = RemoteViews(context.packageName, R.layout.widget_board).apply {
        setTextViewText(R.id.updated, context.getString(R.string.updating))
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

    private fun bindList(views: RemoteViews, state: WidgetState, widgetId: Int) {
        views.setRemoteAdapter(R.id.board_list, WidgetIntents.listService(context, widgetId))
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
