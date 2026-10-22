package io.github.veganmarshmallow.taskboard.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.board.BoardLinks
import io.github.veganmarshmallow.taskboard.core.error.ErrorMessages
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState

/**
 * Builds the widget's outer RemoteViews (header, error banner, list container) from a [WidgetState]
 * and pushes them to every widget instance. The list rows themselves come from [BoardListFactory].
 */
class WidgetRenderer(private val context: Context) {
    private val manager = AppWidgetManager.getInstance(context)
    private val time = TimeText(context)
    private val messages = ErrorMessages(time::format)

    fun widgetIds(): IntArray = manager.getAppWidgetIds(ComponentName(context, BoardWidgetProvider::class.java))

    fun render(state: WidgetState, ids: IntArray = widgetIds()) {
        for (id in ids) manager.updateAppWidget(id, build(state, id))
        if (ids.isNotEmpty()) manager.notifyAppWidgetViewDataChanged(ids, R.id.board_list)
    }

    /** Shows "Updating…" in the header while a refresh runs, without rebuilding the list. */
    fun showUpdating(ids: IntArray = widgetIds()) {
        val views = RemoteViews(context.packageName, R.layout.widget_board)
        views.setTextViewText(R.id.updated, context.getString(R.string.updating))
        for (id in ids) manager.partiallyUpdateAppWidget(id, views)
    }

    private fun build(state: WidgetState, widgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_board)
        views.setOnClickPendingIntent(R.id.refresh, WidgetIntents.refresh(context))
        views.setOnClickPendingIntent(R.id.title, WidgetIntents.openUrl(context, BoardLinks().openIssues()))
        views.setTextViewText(R.id.updated, updatedText(state))

        views.setRemoteAdapter(R.id.board_list, WidgetIntents.listService(context, widgetId))
        views.setPendingIntentTemplate(R.id.board_list, WidgetIntents.rowTemplate(context))
        val hasBoard = state.board != null
        views.setViewVisibility(R.id.board_list, if (hasBoard) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.board_empty, if (hasBoard) View.GONE else View.VISIBLE)
        views.setTextViewText(
            R.id.board_empty,
            context.getString(if (state.error != null) R.string.no_data else R.string.loading),
        )

        val error = state.error
        if (error == null) {
            views.setViewVisibility(R.id.error, View.GONE)
        } else {
            views.setViewVisibility(R.id.error, View.VISIBLE)
            views.setTextViewText(R.id.error, messages.widgetMessage(error))
            val tap = if (messages.opensSetup(error)) WidgetIntents.setup(context) else WidgetIntents.refresh(context)
            views.setOnClickPendingIntent(R.id.error, tap)
        }
        return views
    }

    private fun updatedText(state: WidgetState): String {
        val fetched = state.board?.fetchedAt ?: return context.getString(R.string.never_updated)
        return context.getString(R.string.last_updated, time.format(fetched))
    }
}
