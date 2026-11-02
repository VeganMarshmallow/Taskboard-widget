package io.github.veganmarshmallow.taskboard.widget

import android.appwidget.AppWidgetManager
import android.content.Context

/** A placed widget's size as its launcher reports it (#49). */
object WidgetSize {
    /**
     * The widget's narrowest width in dp (its portrait width), or null when the launcher has not sent a size yet.
     * The rows must fit there; the wider landscape width can only give them more room.
     */
    fun widthDp(context: Context, widgetId: Int): Int? =
        AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
            .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            .takeIf { it > 0 }
}
