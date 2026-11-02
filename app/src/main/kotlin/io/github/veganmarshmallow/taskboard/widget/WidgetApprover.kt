package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger
import io.github.veganmarshmallow.taskboard.core.work.TimeLimit
import io.github.veganmarshmallow.taskboard.core.work.toOutcome
import io.github.veganmarshmallow.taskboard.data.AppGraph
import java.time.Duration

/**
 * Approving a task (adding `ready-for-claude`). Blocking, so always off the main thread: used by the
 * widget's Approve job ([BoardJobService]) and the preview's Approve button (`PreviewWorker`).
 */
class WidgetApprover(private val context: Context) {

    /**
     * Adds the label within [APPROVE_BUDGET], then asks for a refresh (also after a failure).
     * Returns the failure, if any, for the caller to report.
     */
    fun approve(issue: Int): Outcome<Unit> {
        val api = AppGraph.apiOrNull(context) ?: return Outcome.Failure(BoardError.NoToken)
        val result = TimeLimit().run(APPROVE_BUDGET) { api.approve(issue) }.toOutcome()
        BoardJobs.refresh(context, RefreshTrigger.MANUAL)
        return result
    }

    /** The widget row's Approve: [approve], then a toast with the result. */
    fun approveAndToast(issue: Int) = ApproveToast.show(context, issue, approve(issue))

    private companion object {
        val APPROVE_BUDGET: Duration = Duration.ofSeconds(30)
    }
}
