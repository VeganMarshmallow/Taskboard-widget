package io.github.veganmarshmallow.taskboard.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.veganmarshmallow.taskboard.core.work.BoardJob
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger

/**
 * After this app is updated (a new APK installed over the old one), restarts the widget's refresh,
 * so a widget left on an error recovers by itself (#29).
 *
 * The widget's own onUpdate, which the system also sends after an update, is not enough on its own:
 * - the update killed every process of the old version, so a refresh it was running is dead, but
 *   its mark still looks live for up to a minute and onUpdate skips its refresh meanwhile;
 * - a refresh job pending from the old version keeps that version's constraints (before #29, a
 *   *validated* network, which a VPN may never get), and a retry never replaces it.
 */
class AppUpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        if (WidgetRenderer(app).widgetIds().isEmpty()) return // no widget, nothing to recover
        restartRefresh(app)
    }

    companion object {
        /**
         * Replaces any pending refresh with one built by this version (it waits for any network
         * with internet), and schedules a sweep that turns a dead run's "updating…" into an error.
         */
        fun restartRefresh(context: Context) {
            val app = context.applicationContext
            BoardJobs.scheduleSweep(app)
            BoardJobs.schedule(app, BoardJob.Refresh(RefreshTrigger.AUTOMATIC))
        }
    }
}
