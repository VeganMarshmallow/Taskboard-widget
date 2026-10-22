package io.github.veganmarshmallow.taskboard.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.data.TabStore

/** Periodic updates (updatePeriodMillis) and widget placement: draw the cached state, then refresh. */
class BoardWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val app = context.applicationContext
        // New widget ids always get the cached board, even when a refresh is already queued.
        BackgroundWork.refresh(goAsync(), app) { WidgetRenderer(app).render(StateStore(app).load(), appWidgetIds) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        TabStore(context).clear(appWidgetIds)
    }
}
