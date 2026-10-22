package io.github.veganmarshmallow.taskboard.widget

import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.board.Section

/** The message (a string resource id) an empty tab shows. */
object EmptyText {
    fun of(section: Section): Int = when (section) {
        Section.NEEDS_YOU -> R.string.empty_needs_you
        Section.TODO -> R.string.empty_todo
        Section.IN_PROGRESS -> R.string.empty_in_progress
        Section.BLOCKED -> R.string.empty_blocked
        Section.IN_REVIEW -> R.string.empty_in_review
        Section.DONE -> R.string.empty_done
    }
}
