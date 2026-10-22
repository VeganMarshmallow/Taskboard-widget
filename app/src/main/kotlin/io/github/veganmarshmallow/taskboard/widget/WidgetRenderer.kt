package io.github.veganmarshmallow.taskboard.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState

/** Pushes [WidgetViews] to every widget instance and tells the list to reload its rows. */
class WidgetRenderer(private val context: Context) {
    private val manager = AppWidgetManager.getInstance(context)
    private val views = WidgetViews(context)

    fun widgetIds(): IntArray = manager.getAppWidgetIds(ComponentName(context, BoardWidgetProvider::class.java))

    fun render(state: WidgetState, ids: IntArray = widgetIds()) {
        for (id in ids) manager.updateAppWidget(id, views.build(state, id))
        if (ids.isNotEmpty()) manager.notifyAppWidgetViewDataChanged(ids, R.id.board_list)
    }

    /** Shows "updating…" in the header while a refresh runs, without rebuilding the list. */
    fun showUpdating(ids: IntArray = widgetIds()) {
        val partial = views.updating()
        for (id in ids) manager.partiallyUpdateAppWidget(id, partial)
    }
}
