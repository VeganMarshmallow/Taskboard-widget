package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.board.CountBadge
import io.github.veganmarshmallow.taskboard.core.board.Section

/**
 * A tab's words, from string resources so they can be translated: the short [name] the tab shows,
 * the full [title] read out, and the label "<name> <count>" with the count capped by [CountBadge].
 */
object TabText {
    /** The short name on the tab, kept short so three tabs fit a row of the narrowest widget. */
    fun name(section: Section): Int = when (section) {
        Section.NEEDS_YOU -> R.string.tab_needs_you
        Section.TODO -> R.string.tab_todo
        Section.IN_PROGRESS -> R.string.tab_in_progress
        Section.BLOCKED -> R.string.tab_blocked
        Section.DONE -> R.string.tab_done
    }

    /** The full name, for the content description. */
    fun title(section: Section): Int = when (section) {
        Section.NEEDS_YOU -> R.string.tab_title_needs_you
        Section.TODO -> R.string.tab_title_todo
        Section.IN_PROGRESS -> R.string.tab_title_in_progress
        Section.BLOCKED -> R.string.tab_title_blocked
        Section.DONE -> R.string.tab_title_done
    }

    fun label(context: Context, section: Section, count: Int): String =
        context.getString(R.string.tab_label, context.getString(name(section)), countText(context, CountBadge.of(count)))

    fun countText(context: Context, badge: CountBadge): String = when (badge) {
        is CountBadge.Exact -> badge.count.toString()
        is CountBadge.Capped -> context.getString(R.string.tab_count_capped, badge.max)
    }
}
