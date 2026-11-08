package io.github.veganmarshmallow.taskboard.core.model

/**
 * Every label name used on the board, in one place: rename a board label here and nowhere else.
 * GitHub label names are case-insensitive, so every comparison goes through [normalize].
 */
object Labels {
    const val STATUS_PREFIX = "status:"
    const val TYPE_PREFIX = "type:"

    const val TODO = "$STATUS_PREFIX todo"
    const val IN_PROGRESS = "$STATUS_PREFIX in-progress"
    const val BLOCKED = "$STATUS_PREFIX blocked"
    const val IN_REVIEW = "$STATUS_PREFIX in-review"
    const val DONE = "$STATUS_PREFIX done"
    const val READY_FOR_CLAUDE = "ready-for-claude"
    const val NEEDS_OWNER = "needs-owner"
    const val CREATED_BY_CLAUDE = "created-by-claude"
    /** The owner asked Claude a question (task #82); Claude removes it once it has answered. */
    const val QUESTION_FOR_CLAUDE = "question-for-claude"
    const val PRIORITY_HIGH = "priority: high"
    const val PRIORITY_LOW = "priority: low"

    fun normalize(name: String): String = name.trim().lowercase()

    /** True for any `status:` label ([name] already normalized). */
    fun isStatus(name: String): Boolean = name.startsWith(STATUS_PREFIX)

    /** The `type:` label for a type value, e.g. `type: bug`. */
    fun type(value: String): String = "$TYPE_PREFIX $value"

    /** The value after `type:` ([name] already normalized), trimmed; null when [name] is not a type label. */
    fun typeValue(name: String): String? = name.takeIf { it.startsWith(TYPE_PREFIX) }?.removePrefix(TYPE_PREFIX)?.trim()
}
