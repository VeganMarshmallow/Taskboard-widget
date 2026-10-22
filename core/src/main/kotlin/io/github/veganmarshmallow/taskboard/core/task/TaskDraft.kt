package io.github.veganmarshmallow.taskboard.core.task

import io.github.veganmarshmallow.taskboard.core.model.Priority

/** What the user has typed into the New task form, unvalidated. */
data class TaskDraft(
    val title: String = "",
    val goal: String = "",
    val doneWhen: String = "",
    val repo: String = "",
    val type: TaskType = TaskType.FEATURE,
    val priority: Priority = Priority.NORMAL,
    val approveNow: Boolean = false,
) {
    companion object {
        val EMPTY = TaskDraft()
    }
}
