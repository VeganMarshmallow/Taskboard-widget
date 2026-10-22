package io.github.veganmarshmallow.taskboard.core.task

/** The issue form's Type dropdown. [value] is what the form writes and what follows `type: ` in the label. */
enum class TaskType(val value: String) {
    FEATURE("feature"),
    BUG("bug"),
    REFACTOR("refactor"),
    RESEARCH("research");

    val label: String get() = "type: $value"
}
