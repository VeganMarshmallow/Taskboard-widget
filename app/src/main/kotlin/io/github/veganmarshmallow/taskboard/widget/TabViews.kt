package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import android.widget.RemoteViews
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.board.TabLabel

/** Binds the six status tabs: label with count, highlight on [selected], and a tap that selects the tab. */
class TabViews(private val context: Context) {
    fun bind(views: RemoteViews, board: Board?, selected: Section, widgetId: Int) {
        for (section in Section.entries) {
            val id = viewId(section)
            val count = board?.count(section) ?: 0
            views.setTextViewText(id, if (board == null) section.shortLabel else TabLabel.of(section, count))
            val isSelected = section == selected
            val description = context.getString(R.string.tab_description, section.title, count)
            views.setContentDescription(id, if (isSelected) description + context.getString(R.string.tab_selected) else description)
            views.setInt(id, "setBackgroundResource", if (isSelected) R.drawable.tab_selected_background else R.drawable.tab_background)
            views.setTextColor(id, context.getColor(if (isSelected) R.color.widget_accent_text else R.color.widget_header_text))
            views.setOnClickPendingIntent(id, WidgetIntents.selectTab(context, widgetId, section))
        }
    }

    companion object {
        fun viewId(section: Section): Int = when (section) {
            Section.NEEDS_YOU -> R.id.tab_needs_you
            Section.TODO -> R.id.tab_todo
            Section.IN_PROGRESS -> R.id.tab_in_progress
            Section.BLOCKED -> R.id.tab_blocked
            Section.IN_REVIEW -> R.id.tab_in_review
            Section.DONE -> R.id.tab_done
        }
    }
}
