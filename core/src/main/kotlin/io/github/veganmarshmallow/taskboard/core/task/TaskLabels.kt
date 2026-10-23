package io.github.veganmarshmallow.taskboard.core.task

import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.model.Priority

/** Labels for a new task: `status: todo`, its type, a priority unless normal, and `ready-for-claude` if approved now. */
object TaskLabels {
    fun of(type: TaskType, priority: Priority, approveNow: Boolean): List<String> = buildList {
        add(Labels.TODO)
        add(type.label)
        when (priority) {
            Priority.HIGH -> add(Labels.PRIORITY_HIGH)
            Priority.LOW -> add(Labels.PRIORITY_LOW)
            Priority.NORMAL -> Unit
        }
        if (approveNow) add(Labels.READY_FOR_CLAUDE)
    }
}
