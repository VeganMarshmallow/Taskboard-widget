package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.theme.DayNightColor
import io.github.veganmarshmallow.taskboard.theme.WidgetPalette

/**
 * Binds the five status tabs: label with count, highlight on [selected], and a tap that selects the tab.
 * The highlight shows the slot's highlighted label instead of recolouring one from code, so the
 * launcher keeps resolving the colours itself (they follow a light/dark switch; see widget_tabs.xml).
 * A theme other than the layout's own (#46) swaps the highlight drawable and the label colours.
 */
class TabViews(private val context: Context) {
    private val colors = DayNightColor(context)

    fun bind(views: RemoteViews, board: Board?, selected: Section, widgetId: Int, palette: WidgetPalette = WidgetPalette.CALM) {
        for (section in Section.entries) {
            val ids = ids(section)
            val isSelected = section == selected
            val label = TabText.styledLabel(context, section, board?.count(section))
            if (!palette.isLayoutDefault) recolour(views, ids, palette)
            views.setTextViewText(ids.label, label)
            views.setTextViewText(ids.selectedLabel, label)
            views.setViewVisibility(ids.label, if (isSelected) View.GONE else View.VISIBLE)
            views.setViewVisibility(ids.selectedLabel, if (isSelected) View.VISIBLE else View.GONE)
            views.setContentDescription(ids.slot, description(section, board, isSelected))
            views.setOnClickPendingIntent(ids.slot, WidgetIntents.selectTab(context, widgetId, section))
        }
    }

    private fun recolour(views: RemoteViews, ids: Ids, palette: WidgetPalette) {
        views.setInt(ids.label, "setBackgroundResource", palette.tabBackground)
        views.setInt(ids.selectedLabel, "setBackgroundResource", palette.tabSelectedBackground)
        colors.setTextColor(views, ids.selectedLabel, palette.tabSelectedText)
        colors.setTextColor(views, ids.label, palette.tabText)
    }

    /** Full name and count, e.g. "In progress: 2, selected". No count before the first load. */
    private fun description(section: Section, board: Board?, isSelected: Boolean): String {
        val title = context.getString(TabText.title(section))
        val base = if (board == null) title else context.getString(R.string.tab_description, title, board.count(section))
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
            Section.DONE -> Ids(R.id.tab_done, R.id.tab_done_label, R.id.tab_done_selected)
        }
    }
}
