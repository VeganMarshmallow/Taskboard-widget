package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.board.TabLabel

/**
 * Binds the six status tabs: label with count, highlight on [selected], and a tap that selects the tab.
 * The highlight shows the slot's highlighted label instead of recolouring one from code, so the
 * launcher keeps resolving the colours itself (they follow a light/dark switch; see widget_tabs.xml).
 */
class TabViews(private val context: Context) {
    fun bind(views: RemoteViews, board: Board?, selected: Section, widgetId: Int) {
        for (section in Section.entries) {
            val ids = ids(section)
            val isSelected = section == selected
            val label = if (board == null) section.shortLabel else TabLabel.of(board, section)
            views.setTextViewText(ids.label, label)
            views.setTextViewText(ids.selectedLabel, label)
            views.setViewVisibility(ids.label, if (isSelected) View.GONE else View.VISIBLE)
            views.setViewVisibility(ids.selectedLabel, if (isSelected) View.VISIBLE else View.GONE)
            views.setContentDescription(ids.slot, description(section, board, isSelected))
            views.setOnClickPendingIntent(ids.slot, WidgetIntents.selectTab(context, widgetId, section))
        }
    }

    /** Full name and count, e.g. "In progress: 2, selected". No count before the first load. */
    private fun description(section: Section, board: Board?, isSelected: Boolean): String {
        val base = if (board == null) section.title else context.getString(R.string.tab_description, section.title, board.count(section))
        return if (isSelected) base + context.getString(R.string.tab_selected) else base
    }

    /** The view ids of one tab: its tappable slot, and its plain and highlighted labels. */
    class Ids(val slot: Int, val label: Int, val selectedLabel: Int)

    companion object {
        fun ids(section: Section): Ids = when (section) {
            Section.NEEDS_YOU -> Ids(R.id.tab_needs_you, R.id.tab_needs_you_label, R.id.tab_needs_you_selected)
            Section.TODO -> Ids(R.id.tab_todo, R.id.tab_todo_label, R.id.tab_todo_selected)
            Section.IN_PROGRESS -> Ids(R.id.tab_in_progress, R.id.tab_in_progress_label, R.id.tab_in_progress_selected)
            Section.BLOCKED -> Ids(R.id.tab_blocked, R.id.tab_blocked_label, R.id.tab_blocked_selected)
            Section.IN_REVIEW -> Ids(R.id.tab_in_review, R.id.tab_in_review_label, R.id.tab_in_review_selected)
            Section.DONE -> Ids(R.id.tab_done, R.id.tab_done_label, R.id.tab_done_selected)
        }
    }
}
