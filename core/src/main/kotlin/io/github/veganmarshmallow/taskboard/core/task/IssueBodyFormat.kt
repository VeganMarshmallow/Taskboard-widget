package io.github.veganmarshmallow.taskboard.core.task

import io.github.veganmarshmallow.taskboard.core.model.Repo

/**
 * Writes an issue body in exactly the shape GitHub's "Task for Claude" issue form produces
 * (`.github/ISSUE_TEMPLATE/task.yml` in claude-workspace), so the scheduled runs parse tasks
 * from the app and from the form the same way:
 *
 * ```
 * ### Repo
 *
 * owner/name
 *
 * ### Type
 * ...
 * ```
 *
 * Empty fields read `_No response_`, as on the form. User text keeps its markdown, except that a
 * line starting with `###` is escaped (`\###`): it would otherwise read as one of the form's
 * section headings and cut the section short.
 */
object IssueBodyFormat {
    const val NO_RESPONSE = "_No response_"
    const val REPO = "Repo"
    const val TYPE = "Type"
    const val GOAL = "What should be done"
    const val DONE_WHEN = "Done when"
    const val NOTES = "Notes"

    private val SECTION_HEADING = Regex("^( {0,3})(###)", RegexOption.MULTILINE)

    fun build(repo: Repo?, type: TaskType, goal: String, doneWhen: String, notes: String = ""): String =
        listOf(
            REPO to (repo?.fullName ?: ""),
            TYPE to type.value,
            GOAL to goal,
            DONE_WHEN to doneWhen,
            NOTES to notes,
        ).joinToString("\n\n") { (heading, value) -> "### $heading\n\n${field(value)}" }

    /** Normalized user text for one section, or [NO_RESPONSE] when blank. */
    fun field(value: String): String {
        val text = value.replace("\r\n", "\n").replace('\r', '\n').trim()
        return if (text.isEmpty()) NO_RESPONSE else text.replace(SECTION_HEADING, "$1\\\\$2")
    }
}
