package io.github.veganmarshmallow.taskboard.newtask

import android.app.Activity
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.model.Priority
import io.github.veganmarshmallow.taskboard.core.task.TaskDraft
import io.github.veganmarshmallow.taskboard.core.task.TaskDraftValidator.Field
import io.github.veganmarshmallow.taskboard.core.task.TaskType

/** Moves a [TaskDraft] in and out of the form's views, and shows field errors. No logic. */
class DraftForm(activity: Activity) {
    val title: EditText = activity.findViewById(R.id.task_title)
    val goal: EditText = activity.findViewById(R.id.task_goal)
    val doneWhen: EditText = activity.findViewById(R.id.task_done_when)
    val repo: AutoCompleteTextView = activity.findViewById(R.id.task_repo)
    val type: Spinner = activity.findViewById(R.id.task_type)
    val priority: RadioGroup = activity.findViewById(R.id.task_priority)
    val approve: CheckBox = activity.findViewById(R.id.task_approve)

    fun read() = TaskDraft(
        title = title.text.toString(),
        goal = goal.text.toString(),
        doneWhen = doneWhen.text.toString(),
        repo = repo.text.toString(),
        type = TaskType.entries.getOrElse(type.selectedItemPosition) { TaskType.FEATURE },
        priority = when (priority.checkedRadioButtonId) {
            R.id.priority_high -> Priority.HIGH
            R.id.priority_low -> Priority.LOW
            else -> Priority.NORMAL
        },
        approveNow = approve.isChecked,
    )

    fun write(draft: TaskDraft) {
        title.setText(draft.title)
        goal.setText(draft.goal)
        doneWhen.setText(draft.doneWhen)
        repo.setText(draft.repo, false) // false: restoring text must not pop the suggestions open
        type.setSelection(draft.type.ordinal)
        priority.check(
            when (draft.priority) {
                Priority.HIGH -> R.id.priority_high
                Priority.LOW -> R.id.priority_low
                Priority.NORMAL -> R.id.priority_normal
            },
        )
        approve.isChecked = draft.approveNow
    }

    fun showErrors(errors: Map<Field, String>) {
        title.error = errors[Field.TITLE]
        goal.error = errors[Field.GOAL]
        repo.error = errors[Field.REPO]
        when {
            Field.TITLE in errors -> title.requestFocus()
            Field.REPO in errors -> repo.requestFocus()
            Field.GOAL in errors -> goal.requestFocus()
        }
    }

    fun setEnabled(enabled: Boolean) {
        listOf(title, goal, doneWhen, repo, type, approve).forEach { it.isEnabled = enabled }
        for (i in 0 until priority.childCount) priority.getChildAt(i).isEnabled = enabled
    }
}
