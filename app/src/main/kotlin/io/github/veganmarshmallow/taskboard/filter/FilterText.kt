package io.github.veganmarshmallow.taskboard.filter

import android.content.Context
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.filter.BoardFilter
import io.github.veganmarshmallow.taskboard.core.model.Priority
import java.util.Locale

/** The filter's wording (#59): chip labels, and the one-line summary the widget shows when it is on. */
class FilterText(private val context: Context) {
    /** A repo by its name alone (`taskboard-widget`): the owner is always the same and takes room. */
    fun repo(fullName: String): String = fullName.substringAfter('/').ifBlank { fullName }

    /** `bug` → "Bug". */
    fun type(value: String): String = value.replaceFirstChar { it.titlecase(Locale.getDefault()) }

    fun priority(priority: Priority): String = context.getString(
        when (priority) {
            Priority.HIGH -> R.string.filter_priority_high
            Priority.NORMAL -> R.string.filter_priority_normal
            Priority.LOW -> R.string.filter_priority_low
        },
    )

    /** E.g. `Bug, taskboard-widget, “login”`: every picked value, in the sheet's order. */
    fun summary(filter: BoardFilter): String {
        val parts = filter.repos.map(::repo) + filter.types.map(::type) + filter.priorities.sorted().map(::priority) +
            listOfNotNull(filter.text.trim().takeIf { it.isNotEmpty() }?.let { context.getString(R.string.filter_text_quoted, it) })
        return parts.joinToString(context.getString(R.string.filter_separator))
    }

    /** "Showing 3 of 12 tasks". */
    fun showing(shown: Int, total: Int): String = context.resources.getQuantityString(R.plurals.filter_showing, total, shown, total)
}
