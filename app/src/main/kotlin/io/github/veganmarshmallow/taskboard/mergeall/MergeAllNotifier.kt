package io.github.veganmarshmallow.taskboard.mergeall

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.merge.MergeDialogs
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllEnd
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllState
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllText
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.widget.WidgetIntents

/**
 * The notification when a Merge all run ends (#57): finished, cancelled, or which PR stopped it and
 * why (tapping it opens that PR). Without the notification permission (Android 13+) the widget's
 * line still says how it went.
 */
class MergeAllNotifier(private val context: Context) {
    private val text = MergeAllText(MergeDialogs(AppGraph.messages(context)))

    fun ended(state: MergeAllState) {
        val end = state.end ?: return
        if (!allowed(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.merge_all_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val body = text.result(state).orEmpty()
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_merge)
            .setContentTitle(context.getString(title(end)))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
        if (end is MergeAllEnd.Stopped) {
            val url = state.plan[end.at].pr.webUrl
            builder.setContentIntent(PendingIntent.getActivity(context, RC_OPEN, WidgetIntents.viewUrl(url), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        try {
            manager.notify(ID, builder.build())
        } catch (e: SecurityException) {
            // The permission was taken back between the check and the post: the widget still shows it.
        }
    }

    private fun title(end: MergeAllEnd): Int = when (end) {
        MergeAllEnd.Done -> R.string.merge_all_done_title
        MergeAllEnd.Cancelled -> R.string.merge_all_cancelled_title
        is MergeAllEnd.Stopped -> R.string.merge_all_stopped_title
    }

    companion object {
        private const val CHANNEL = "merge_all"
        private const val ID = 57
        private const val RC_OPEN = 57

        /** Android 13+ asks for the permission; earlier versions always allow it. */
        fun allowed(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}
