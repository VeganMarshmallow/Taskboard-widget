package io.github.veganmarshmallow.taskboard.merge

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.merge.MergeButton
import io.github.veganmarshmallow.taskboard.core.merge.MergeDialog
import io.github.veganmarshmallow.taskboard.core.merge.MergeDialogs
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.merge.MergeService
import io.github.veganmarshmallow.taskboard.core.merge.MergeStep
import io.github.veganmarshmallow.taskboard.core.merge.PullRequest
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger
import io.github.veganmarshmallow.taskboard.core.work.TimeLimit
import io.github.veganmarshmallow.taskboard.core.work.Timed
import io.github.veganmarshmallow.taskboard.core.work.crashError
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.setup.SetupActivity
import io.github.veganmarshmallow.taskboard.widget.BoardJobs
import io.github.veganmarshmallow.taskboard.widget.Connectivity
import io.github.veganmarshmallow.taskboard.widget.WidgetIntents
import java.time.Duration
import java.util.concurrent.Executors

/**
 * The Merge button's flow (task #30), shared by the widget row and the task preview: one dialog
 * that shows "checking", then the confirmation (PR title, repo, number) or why it can't merge,
 * then "merging". All decisions are in core ([MergeService], [MergeDialogs]); this class runs
 * them off the main thread and shows the result. After a merge attempt the widget refreshes.
 * The owner always confirms: this button is the owner merging, never Claude.
 */
class MergeFlow(
    private val activity: Activity,
    private val issue: Int,
    private val onMerged: (PullRequest) -> Unit = {},
    private val onClosed: () -> Unit = {},
) {
    private val app = activity.applicationContext
    private val dialogs = MergeDialogs(AppGraph.messages(activity))
    private val main = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    private var view: MergeDialogView? = null
    private var service: MergeService? = null
    private var checked: PullRequest? = null
    private var closed = false

    /** The step on screen now; read by instrumented tests. */
    var shown: MergeDialog? = null
        private set

    /** The dialog's content, for instrumented tests to press its buttons. */
    fun dialogRootForTest(): android.view.View? = view?.root

    fun start() {
        val api = AppGraph.apiOrNull(app)
        when {
            api == null -> show(dialogs.refusal(MergeProblem.Failed(BoardError.NoToken), issue))
            !Connectivity.isOnline(app) -> show(dialogs.refusal(MergeProblem.Failed(BoardError.Network), issue))
            else -> {
                val merge = MergeService(api).also { service = it }
                show(dialogs.checking(issue))
                runTimed(PREPARE_BUDGET, { merge.prepare(issue) }) { step ->
                    when (step) {
                        is MergeStep.Ok -> { checked = step.value; show(dialogs.confirm(step.value)) }
                        is MergeStep.Stop -> show(dialogs.refusal(step.problem, issue))
                    }
                }
            }
        }
    }

    /** Closes the dialog and drops any result still on its way. */
    fun close() {
        if (closed) return
        closed = true
        dialog?.dismiss()
        dialog = null
        onClosed()
    }

    private fun onButton(button: MergeButton) {
        when (button) {
            MergeButton.MERGE -> merge()
            MergeButton.CANCEL, MergeButton.CLOSE -> close()
            MergeButton.OPEN_GITHUB, MergeButton.EDIT_TOKEN -> { shown?.link?.let(::open); close() }
            MergeButton.SET_UP -> { activity.startActivity(Intent(activity, SetupActivity::class.java)); close() }
        }
    }

    private fun merge() {
        val pr = checked ?: return
        val merge = service ?: return
        show(dialogs.merging(pr))
        val work = {
            merge.merge(pr).also { step ->
                // Even if the screen is gone by now: refresh the board, and say it merged.
                BoardJobs.refresh(app, RefreshTrigger.MANUAL)
                if (step is MergeStep.Ok) main.post { Toast.makeText(app, dialogs.merged(pr, issue), Toast.LENGTH_LONG).show() }
            }
        }
        runTimed(MERGE_BUDGET, work) { step ->
            when (step) {
                is MergeStep.Ok -> { onMerged(pr); close() }
                is MergeStep.Stop -> {
                    if (step.problem == MergeProblem.Failed(BoardError.Timeout)) BoardJobs.refresh(app, RefreshTrigger.MANUAL)
                    show(dialogs.mergeFailed(step.problem, issue))
                }
            }
        }
    }

    private fun show(next: MergeDialog) {
        if (closed || activity.isFinishing || activity.isDestroyed) return
        shown = next
        val current = view ?: createDialog()
        current.bind(next)
        // A running merge must finish; anything else can be dismissed with Back.
        dialog?.setCancelable(next.kind != MergeDialog.Kind.PROGRESS || next.secondary != null)
    }

    @SuppressLint("InflateParams") // a Dialog's content view has no parent to inflate against
    private fun createDialog(): MergeDialogView {
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_merge, null, false)
        val bound = MergeDialogView(root, ::onButton)
        val d = Dialog(activity, R.style.Theme_Taskboard_MergeDialog)
        d.setContentView(root)
        d.window?.setLayout(dialogWidth(), ViewGroup.LayoutParams.WRAP_CONTENT)
        d.setOnCancelListener { close() }
        d.show()
        dialog = d
        view = bound
        return bound
    }

    private fun dialogWidth(): Int {
        val metrics = activity.resources.displayMetrics
        return minOf(metrics.widthPixels - (32 * metrics.density).toInt(), (420 * metrics.density).toInt())
    }

    private fun open(url: String) {
        try {
            activity.startActivity(WidgetIntents.viewUrl(url))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(app, R.string.no_browser, Toast.LENGTH_LONG).show()
        }
    }

    /** Runs [work] on a background thread with a time limit, then [done] on the main thread unless closed. */
    private fun <T> runTimed(limit: Duration, work: () -> MergeStep<T>, done: (MergeStep<T>) -> Unit) {
        executor.execute {
            val step = when (val timed = TimeLimit().run(limit, work)) {
                is Timed.Done -> timed.value
                is Timed.Crashed -> MergeStep.Stop(MergeProblem.Failed(crashError(timed.error)))
                Timed.TimedOut -> MergeStep.Stop(MergeProblem.Failed(BoardError.Timeout))
            }
            main.post { if (!closed) done(step) }
        }
    }

    private companion object {
        /** Finding the PR is a handful of requests plus up to two mergeability re-polls. */
        val PREPARE_BUDGET: Duration = Duration.ofSeconds(45)
        val MERGE_BUDGET: Duration = Duration.ofSeconds(30)

        val executor = Executors.newCachedThreadPool { r -> Thread(r, "taskboard-merge").apply { isDaemon = true } }
    }
}
