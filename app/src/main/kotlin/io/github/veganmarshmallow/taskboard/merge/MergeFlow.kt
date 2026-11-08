package io.github.veganmarshmallow.taskboard.merge

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.merge.MergeButton
import io.github.veganmarshmallow.taskboard.core.merge.MergeDialog
import io.github.veganmarshmallow.taskboard.core.merge.MergeDialogs
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.merge.MergeService
import io.github.veganmarshmallow.taskboard.core.merge.MergeStep
import io.github.veganmarshmallow.taskboard.core.merge.PullRequest
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.WorkPaceStore
import io.github.veganmarshmallow.taskboard.setup.SetupActivity
import io.github.veganmarshmallow.taskboard.widget.BoardJobs
import io.github.veganmarshmallow.taskboard.widget.Connectivity
import java.time.Duration

/**
 * The Merge button's flow (task #30), shared by the widget row and the task preview: one dialog
 * that shows "checking", then the confirmation (PR title, repo, number) or why it can't merge,
 * then "merging". All decisions are in core ([MergeService], [MergeDialogs]); the dialog window and
 * the background work are [MergeDialogHost]'s. After a merge attempt the widget refreshes.
 * The owner always confirms: this button is the owner merging, never Claude.
 */
class MergeFlow(
    private val activity: Activity,
    private val issue: Int,
    private val onMerged: (PullRequest) -> Unit = {},
    onClosed: () -> Unit = {},
) {
    private val app = activity.applicationContext
    private val dialogs = MergeDialogs(AppGraph.messages(activity))
    private val main = Handler(Looper.getMainLooper())
    private val host = MergeDialogHost(activity, ::onButton, onClosed)
    private var service: MergeService? = null
    private var checked: PullRequest? = null

    /** Set on the first Merge tap: a second tap queued before the button hid never sends a second PUT. */
    private var mergeStarted = false

    /** The step on screen now; read by instrumented tests. */
    val shown: MergeDialog? get() = host.shown

    /** The dialog's content, for instrumented tests to press its buttons. */
    fun dialogRootForTest(): android.view.View? = host.root

    fun start() {
        val api = AppGraph.apiOrNull(app)
        when {
            api == null -> host.show(dialogs.refusal(MergeProblem.Failed(BoardError.NoToken), issue))
            !Connectivity.isOnline(app) -> host.show(dialogs.refusal(MergeProblem.Failed(BoardError.Network), issue))
            else -> {
                val merge = MergeService(api, pace = WorkPaceStore(app).cached()).also { service = it } // #83: the merge commit carries the pace
                host.show(dialogs.checking(issue))
                host.runTimed(PREPARE_BUDGET, { merge.prepare(issue) }) { step ->
                    when (step) {
                        is MergeStep.Ok -> { checked = step.value; host.show(dialogs.confirm(step.value)) }
                        is MergeStep.Stop -> host.show(dialogs.refusal(step.problem, issue))
                    }
                }
            }
        }
    }

    /** Closes the dialog and drops any result still on its way. */
    fun close() = host.close()

    private fun onButton(button: MergeButton) {
        when (button) {
            MergeButton.MERGE -> merge()
            MergeButton.CANCEL, MergeButton.CLOSE, MergeButton.MERGE_ALL -> close()
            MergeButton.OPEN_GITHUB, MergeButton.EDIT_TOKEN -> { shown?.link?.let(host::open); close() }
            MergeButton.SET_UP -> { activity.startActivity(Intent(activity, SetupActivity::class.java)); close() }
        }
    }

    private fun merge() {
        if (mergeStarted) return
        val pr = checked ?: return
        val merge = service ?: return
        mergeStarted = true
        host.show(dialogs.merging(pr))
        val work = {
            merge.merge(pr).also { step ->
                // Even if the screen is gone by now: refresh the board, and say it merged.
                BoardJobs.refresh(app, RefreshTrigger.MANUAL)
                if (step is MergeStep.Ok) main.post { Toast.makeText(app, dialogs.merged(pr, issue), Toast.LENGTH_LONG).show() }
            }
        }
        host.runTimed(MERGE_BUDGET, work) { step ->
            when (step) {
                is MergeStep.Ok -> { onMerged(pr); close() }
                is MergeStep.Stop -> {
                    if (step.problem == MergeProblem.Failed(BoardError.Timeout)) BoardJobs.refresh(app, RefreshTrigger.MANUAL)
                    host.show(dialogs.mergeFailed(step.problem, issue))
                }
            }
        }
    }

    private companion object {
        /** Finding the PR is a handful of requests plus up to two mergeability re-polls. */
        val PREPARE_BUDGET: Duration = Duration.ofSeconds(45)
        val MERGE_BUDGET: Duration = Duration.ofSeconds(30)
    }
}
