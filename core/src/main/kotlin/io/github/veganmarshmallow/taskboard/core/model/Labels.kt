package io.github.veganmarshmallow.taskboard.core.model

/**
 * Label names used on the board. GitHub label names are case-insensitive, so every comparison
 * goes through [normalize].
 */
object Labels {
    const val TODO = "status: todo"
    const val IN_PROGRESS = "status: in-progress"
    const val BLOCKED = "status: blocked"
    const val IN_REVIEW = "status: in-review"
    const val DONE = "status: done"
    const val READY_FOR_CLAUDE = "ready-for-claude"
    const val NEEDS_OWNER = "needs-owner"
    const val PRIORITY_HIGH = "priority: high"
    const val PRIORITY_LOW = "priority: low"

    fun normalize(name: String): String = name.trim().lowercase()
}
