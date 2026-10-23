package io.github.veganmarshmallow.taskboard.newtask

import android.content.Context
import android.widget.Toast
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.task.CreateErrors
import io.github.veganmarshmallow.taskboard.core.task.CreatedIssue
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.widget.WidgetIntents

/** What happens after a submission: a toast with the new number and a widget refresh, or a failure toast. */
object NewTaskEffects {
    fun created(context: Context, created: CreatedIssue) {
        context.sendBroadcast(WidgetIntents.refreshIntent(context))
        Toast.makeText(context, message(context, created), Toast.LENGTH_LONG).show()
    }

    fun failedInBackground(context: Context, result: Outcome<CreatedIssue>) {
        val error = (result as? Outcome.Failure)?.error ?: return
        Toast.makeText(context, failureText(context, error), Toast.LENGTH_LONG).show()
    }

    /** The form's and the toast's failure text; warns when the task may have been created anyway. */
    fun failureText(context: Context, error: BoardError): String {
        val text = context.getString(R.string.task_failed, AppGraph.messages(context).message(error))
        return if (CreateErrors.mayAlreadyExist(error)) text + "\n" + context.getString(R.string.task_may_exist) else text
    }

    fun message(context: Context, created: CreatedIssue): String {
        val base = created.number?.let { context.getString(R.string.task_created, it) }
            ?: context.getString(R.string.task_created_no_number)
        if (created.missingLabels.isEmpty()) return base
        return base + "\n" + context.getString(R.string.task_labels_missing, created.missingLabels.joinToString(", "))
    }
}
