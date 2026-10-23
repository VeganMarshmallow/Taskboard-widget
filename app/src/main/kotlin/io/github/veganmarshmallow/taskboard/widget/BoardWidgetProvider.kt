package io.github.veganmarshmallow.taskboard.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.data.TabStore

/**
 * Periodic updates (updatePeriodMillis) and widget placement: clear a dead refresh, draw the
 * cached state, then schedule a refresh job.
 */
class BoardWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val app = context.applicationContext
        WidgetRefresher(app).recoverIfStale()
        // New widget ids always get the cached board, even while a refresh is running.
        WidgetRenderer(app).render(StateStore(app).load(), appWidgetIds)
        BoardJobs.refresh(app, RefreshTrigger.AUTOMATIC)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        TabStore(context).clear(appWidgetIds)
    }
}
