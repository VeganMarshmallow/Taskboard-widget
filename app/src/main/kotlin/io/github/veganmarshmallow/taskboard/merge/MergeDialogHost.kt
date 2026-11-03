package io.github.veganmarshmallow.taskboard.merge

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.merge.MergeButton
import io.github.veganmarshmallow.taskboard.core.merge.MergeDialog
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.merge.MergeStep
import io.github.veganmarshmallow.taskboard.core.work.TimeLimit
import io.github.veganmarshmallow.taskboard.core.work.Timed
import io.github.veganmarshmallow.taskboard.core.work.crashError
import io.github.veganmarshmallow.taskboard.widget.WidgetIntents
import java.time.Duration
import java.util.concurrent.Executors

/**
 * The merge dialog's window, shared by the Merge button ([MergeFlow], #30) and Merge all
 * ([io.github.veganmarshmallow.taskboard.mergeall.MergeAllFlow], #57): shows each [MergeDialog],
 * runs work off the main thread with a time limit, and drops results that arrive after [close].
 */
class MergeDialogHost(
    private val activity: Activity,
    private val onButton: (MergeButton) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    private var view: MergeDialogView? = null

    var closed = false
        private set

    /** The step on screen now. */
    var shown: MergeDialog? = null
        private set

    /** The dialog's content, for instrumented tests to press its buttons. */
    val root: View? get() = view?.root

    fun show(next: MergeDialog) {
        if (closed || activity.isFinishing || activity.isDestroyed) return
        shown = next
        val current = view ?: createDialog()
        current.bind(next)
        // A running merge must finish; anything else can be dismissed with Back.
        dialog?.setCancelable(next.kind != MergeDialog.Kind.PROGRESS || next.secondary != null)
    }

    /** Closes the dialog and drops any result still on its way. */
    fun close() {
        if (closed) return
        closed = true
        dialog?.dismiss()
        dialog = null
        onClosed()
    }

    /** Hides the dialog but keeps the screen (a permission prompt comes next); [close] still ends it. */
    fun hide() {
        dialog?.setOnCancelListener(null)
        dialog?.dismiss()
        dialog = null
        view = null
    }

    fun open(url: String) {
        try {
            activity.startActivity(WidgetIntents.viewUrl(url))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity.applicationContext, R.string.no_browser, Toast.LENGTH_LONG).show()
        }
    }

    /** Runs [work] on a background thread with a time limit, then [done] on the main thread unless closed. */
    fun <T> runTimed(limit: Duration, work: () -> MergeStep<T>, done: (MergeStep<T>) -> Unit) {
        executor.execute {
            val step = when (val timed = TimeLimit().run(limit, work)) {
                is Timed.Done -> timed.value
                is Timed.Crashed -> MergeStep.Stop(MergeProblem.Failed(crashError(timed.error)))
                Timed.TimedOut -> MergeStep.Stop(MergeProblem.Failed(BoardError.Timeout))
            }
            main.post { if (!closed) done(step) }
        }
    }

    @SuppressLint("InflateParams") // a Dialog's content view has no parent to inflate against
    private fun createDialog(): MergeDialogView {
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_merge, null, false)
        val bound = MergeDialogView(root, onButton)
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

    private companion object {
        val executor = Executors.newCachedThreadPool { r -> Thread(r, "taskboard-merge").apply { isDaemon = true } }
    }
}
