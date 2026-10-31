package io.github.veganmarshmallow.taskboard.core.board

import io.github.veganmarshmallow.taskboard.core.model.Priority

/** Why the owner is needed: in Needs you, or (a QUESTION on a blocked task, #34) in Blocked. */
enum class NeedsYouReason { QUESTION, REVIEW }

/**
 * One row-worthy task. [title] is already cleaned for single-line display. [number] identifies the
 * issue (taps, approve, links) but is not part of the row text.
 */
data class BoardItem(
    val number: Int,
    val title: String,
    val priority: Priority,
    val canApprove: Boolean,
    val needsYouReason: NeedsYouReason? = null,
    /** In review: the row offers Merge (task #30). */
    val canMerge: Boolean = false,
    /** With [canMerge]: whether this PR is the one to merge next, or why not (#45). Null until checked. */
    val mergeLane: MergeLane? = null,
) {
    /** What the widget row shows: just the title, without the leading `#n`. */
    val displayText: String get() = title
}
