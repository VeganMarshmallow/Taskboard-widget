package io.github.veganmarshmallow.taskboard.core.board

/** Widget sections, declared in display order. */
enum class Section(val title: String) {
    NEEDS_YOU("Needs you"),
    IN_PROGRESS("In progress"),
    BLOCKED("Blocked"),
    TODO("Todo"),
}
