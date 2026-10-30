package io.github.veganmarshmallow.taskboard.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.util.Log
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.data.TabStore

/**
 * Periodic updates (updatePeriodMillis), widget placement, app updates and reboots: clear a dead
 * refresh, draw the cached state, then schedule a refresh job.
 */
class BoardWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val app = context.applicationContext
        try {
            WidgetRefresher(app).recoverIfStale()
            // New widget ids always get the cached board, even while a refresh is running.
            WidgetRenderer(app).render(StateStore(app).load(), appWidgetIds)
        } catch (e: RuntimeException) {
            // Drawing must never cost the refresh below: it is how a stuck widget recovers (#29).
            Log.w(TAG, "drawing the cached board failed: ${e.javaClass.simpleName}")
        }
        BoardJobs.refresh(app, RefreshTrigger.AUTOMATIC)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        TabStore(context).clear(appWidgetIds)
    }

    /** The last widget was removed: stop retrying in the background (#29). */
    override fun onDisabled(context: Context) {
        RetryScheduler.stop(context)
    }

    private companion object {
        const val TAG = "BoardWidgetProvider"
    }
}
