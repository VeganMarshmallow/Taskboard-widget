package io.github.veganmarshmallow.taskboard.core.board

/**
 * The widget's tabs, declared in display order. Core is language-neutral: the tab names and label
 * format are string resources in the app (`TabText`), the way empty-tab messages are.
 *
 * Every status tab lists the open issues with that status, except those in [NEEDS_YOU]
 * (questions plus every `status: in-review` issue): Needs you is exclusive, so an item is in one tab
 * only. There is no In review tab: under that rule it could never hold anything (the owner removed
 * it). [DONE] lists issues closed as done within the last 7 days.
 *
 * Removing an entry is safe for stored state: saved tab picks fall back to the default
 * ([TabSelection.parse]) and the cached board is versioned ([WidgetStateCodec]).
 */
enum class Section {
    NEEDS_YOU,
    TODO,
    IN_PROGRESS,
    BLOCKED,
    DONE,
}
