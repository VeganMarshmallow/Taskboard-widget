package io.github.veganmarshmallow.taskboard.mergeall

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.merge.MergeButton
import io.github.veganmarshmallow.taskboard.core.merge.MergeDialog
import io.github.veganmarshmallow.taskboard.core.merge.MergeDialogs
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.merge.MergeStep
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllPlanning
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllText
import io.github.veganmarshmallow.taskboard.core.mergeall.PlannedMerge
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.merge.MergeDialogHost
import io.github.veganmarshmallow.taskboard.setup.SetupActivity
import io.github.veganmarshmallow.taskboard.widget.Connectivity
import java.time.Duration

/**
 * Merge all's dialog (#57): "checking the PRs", then "Merge 5 PRs in this order: …" (or why
 * not), and on Merge all the run starts in the background ([MergeAllJob]) and the dialog closes.
 * On Android 13+ it then asks once for the notification permission, for the end-of-run notification;
 * [onPermissionAsked] lets the activity finish once that prompt is answered.
 */
class MergeAllFlow(private val activity: Activity, onClosed: () -> Unit) {
    private val app = activity.applicationContext
    private val dialogs = MergeDialogs(AppGraph.messages(activity))
    private val text = MergeAllText(dialogs)
    private val host = MergeDialogHost(activity, ::onButton, onClosed)
    private var plan: List<PlannedMerge>? = null
    private var started = false

    /** The step on screen now; read by instrumented tests. */
    val shown: MergeDialog? get() = host.shown

    fun dialogRootForTest(): android.view.View? = host.root

    fun start() {
        val api = AppGraph.apiOrNull(app)
        when {
            api == null -> host.show(text.planFailed(MergeProblem.Failed(BoardError.NoToken)))
            !Connectivity.isOnline(app) -> host.show(text.planFailed(MergeProblem.Failed(BoardError.Network)))
            else -> {
                host.show(text.planning())
                host.runTimed(PLAN_BUDGET, { MergeAllPlanning(api).plan() }) { step ->
                    when (step) {
                        is MergeStep.Stop -> host.show(text.planFailed(step.problem))
                        is MergeStep.Ok -> if (step.value.isEmpty()) {
                            host.show(text.nothingToMerge())
                        } else {
                            plan = step.value
                            host.show(text.confirm(step.value))
                        }
                    }
                }
            }
        }
    }

    fun close() = host.close()

    private fun onButton(button: MergeButton) {
        when (button) {
            MergeButton.MERGE_ALL -> confirm()
            MergeButton.MERGE, MergeButton.CANCEL, MergeButton.CLOSE -> close()
            MergeButton.OPEN_GITHUB, MergeButton.EDIT_TOKEN -> { shown?.link?.let(host::open); close() }
            MergeButton.SET_UP -> { activity.startActivity(Intent(activity, SetupActivity::class.java)); close() }
        }
    }

    private fun confirm() {
        if (started) return
        val planned = plan ?: return
        started = true
        MergeAllJob.start(app, planned)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !MergeAllNotifier.allowed(app)) {
            host.hide()
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST)
        } else {
            close()
        }
    }

    /** The permission prompt was answered (either way): the flow is over. */
    fun onPermissionAsked() = close()

    companion object {
        const val PERMISSION_REQUEST = 57

        /** Every task offering Merge gets the refresh's checks: a handful of requests each. */
        val PLAN_BUDGET: Duration = Duration.ofSeconds(60)
    }
}
