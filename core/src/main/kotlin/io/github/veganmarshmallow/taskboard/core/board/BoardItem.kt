package io.github.veganmarshmallow.taskboard.core.board

import io.github.veganmarshmallow.taskboard.core.model.Priority

/** Why an item is in "Needs you". */
enum class NeedsYouReason { QUESTION, REVIEW }

/** One row-worthy task. [title] is already cleaned for single-line display. */
data class BoardItem(
    val number: Int,
    val title: String,
    val priority: Priority,
    val canApprove: Boolean,
    val needsYouReason: NeedsYouReason? = null,
) {
    val displayText: String get() = "#$number $title"
}
