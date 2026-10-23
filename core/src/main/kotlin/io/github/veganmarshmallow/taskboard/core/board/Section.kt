package io.github.veganmarshmallow.taskboard.core.board

/**
 * The widget's tabs, declared in display order. [title] is the full name; [shortLabel] is what the
 * tab shows, kept short so the tabs fit a narrow widget.
 *
 * Every status tab lists the open issues with that status, except those in [NEEDS_YOU]
 * (questions plus every `status: in-review` issue): Needs you is exclusive, so an item is in one tab
 * only. There is no In review tab: under that rule it could never hold anything (the owner removed
 * it). [DONE] lists issues closed as done within the last 7 days.
 *
 * Removing an entry is safe for stored state: saved tab picks fall back to the default
 * ([TabSelection.parse]) and the cached board is versioned ([WidgetStateCodec]).
 */
enum class Section(val title: String, val shortLabel: String) {
    NEEDS_YOU("Needs you", "Needs you"),
    TODO("Todo", "Todo"),
    IN_PROGRESS("In progress", "Doing"),
    BLOCKED("Blocked", "Blocked"),
    DONE("Done", "Done"),
}
