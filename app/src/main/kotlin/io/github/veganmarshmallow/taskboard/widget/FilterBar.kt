package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.filter.BoardFilter
import io.github.veganmarshmallow.taskboard.filter.FilterText

/**
 * The widget's filter controls (#59): the header's filter button (accent while a filter is on), and
 * under the tabs the "Filtered: Bug, taskboard-widget" line with Clear. Both open the filter sheet.
 */
class FilterBar(private val context: Context) {
    private val text = FilterText(context)

    fun bind(views: RemoteViews, filter: BoardFilter) {
        val open = WidgetIntents.filter(context)
        views.setOnClickPendingIntent(R.id.filter, open)
        views.setImageViewResource(R.id.filter, if (filter.isActive) R.drawable.ic_filter_active else R.drawable.ic_filter)
        views.setContentDescription(
            R.id.filter,
            if (filter.isActive) context.getString(R.string.filter_active_description, filter.activeCount) else context.getString(R.string.filter),
        )
        if (!filter.isActive) {
            views.setViewVisibility(R.id.filter_bar, View.GONE)
            return
        }
        views.setViewVisibility(R.id.filter_bar, View.VISIBLE)
        views.setTextViewText(R.id.filter_bar_text, context.getString(R.string.filter_bar, text.summary(filter)))
        views.setOnClickPendingIntent(R.id.filter_bar_text, open)
        views.setOnClickPendingIntent(R.id.filter_bar_clear, WidgetIntents.clearFilter(context))
    }
}
