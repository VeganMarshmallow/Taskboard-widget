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
import io.github.veganmarshmallow.taskboard.widget.WidgetIntents

/**
 * Posts the merge notifications: Merge all's end (#57) and auto merge's merges and failures (#79).
 * Tapping one opens [Notice.url] (a PR page) when there is one. Without the notification permission
 * (Android 13+) nothing is posted; the widget still shows how it went.
 */
object MergeNotices {
    /** [channel]: the channel's id; [channelName]: its name in Android's settings. [tag]: several notices per [id]. */
    data class Notice(
        val channel: String,
        val channelName: Int,
        val id: Int,
        val title: String,
        val body: String,
        val url: String? = null,
        val tag: String? = null,
    )

    fun post(context: Context, notice: Notice) {
        if (!allowed(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(notice.channel, context.getString(notice.channelName), NotificationManager.IMPORTANCE_DEFAULT))
        try {
            manager.notify(notice.tag, notice.id, build(context, notice))
        } catch (e: SecurityException) {
            // The permission was taken back between the check and the post: the widget still shows it.
        }
    }

    /** The notification for [notice], not posted (its channel is created by [post]). */
    fun build(context: Context, notice: Notice): Notification {
        val builder = Notification.Builder(context, notice.channel)
            .setSmallIcon(R.drawable.ic_merge)
            .setContentTitle(notice.title)
            .setContentText(notice.body)
            .setStyle(Notification.BigTextStyle().bigText(notice.body))
            .setAutoCancel(true)
        notice.url?.let { url ->
            val open = PendingIntent.getActivity(context, notice.id, WidgetIntents.viewUrl(url), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.setContentIntent(open)
        }
        return builder.build()
    }

    /** Android 13+ asks for the permission; earlier versions always allow it. */
    fun allowed(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
