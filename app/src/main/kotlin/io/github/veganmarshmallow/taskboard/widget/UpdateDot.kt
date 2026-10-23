package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.update.UpdateBadge

/** The small "update available" dot in the widget header. Tapping it opens the app's update card. */
object UpdateDot {
    fun bind(views: RemoteViews, context: Context, shown: Boolean = UpdateBadge.isShown(context)) {
        views.setViewVisibility(R.id.update_dot, if (shown) View.VISIBLE else View.GONE)
        if (shown) views.setOnClickPendingIntent(R.id.update_dot, WidgetIntents.updates(context))
    }
}
