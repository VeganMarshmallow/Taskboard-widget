package io.github.veganmarshmallow.taskboard.core.task

import io.github.veganmarshmallow.taskboard.core.model.Labels

/** The issue form's Type dropdown. [value] is what the form writes and what follows `type: ` in the label. */
enum class TaskType(val value: String) {
    FEATURE("feature"),
    BUG("bug"),
    REFACTOR("refactor"),
    RESEARCH("research");

    val label: String get() = Labels.type(value)
}
