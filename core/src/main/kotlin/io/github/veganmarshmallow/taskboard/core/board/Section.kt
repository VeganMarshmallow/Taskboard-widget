package io.github.veganmarshmallow.taskboard.core.board

/**
 * The widget's tabs, declared in display order. [title] is the full name; [shortLabel] is what the
 * tab shows, kept short so six tabs fit a narrow widget.
 *
 * Every status tab lists the open issues with that status, except those in [NEEDS_YOU]
 * (questions plus PRs waiting for review): Needs you is exclusive, so an item is in one tab only.
 * [DONE] lists issues closed as done within the last 7 days.
 */
enum class Section(val title: String, val shortLabel: String) {
    NEEDS_YOU("Needs you", "Needs you"),
    TODO("Todo", "Todo"),
    IN_PROGRESS("In progress", "Doing"),
    BLOCKED("Blocked", "Blocked"),
    IN_REVIEW("In review", "Review"),
    DONE("Done", "Done"),
}
