package io.github.veganmarshmallow.taskboard.setup

import android.Manifest
import android.app.Activity
import android.os.Build
import android.widget.Switch
import android.widget.TextView
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.automerge.AutoMergeJob
import io.github.veganmarshmallow.taskboard.core.pace.WorkPace
import io.github.veganmarshmallow.taskboard.data.AutoMergePrefs
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.mergeall.MergeNotices

/**
 * The auto merge switch in settings (#79), off by default. Turning it on asks for the notification
 * permission (Android 13+; each merge and failure is notified) and polls at once if the last
 * refresh found a PR ready to merge. Its description follows the Work pace shown on the same
 * screen ([showPace], #88): Faster merges ready PRs back to back.
 */
class AutoMergeSection(private val activity: Activity, private val prefs: AutoMergePrefs = AutoMergePrefs(activity)) {
    private val toggle: Switch = activity.findViewById(R.id.auto_merge_switch)
    private val hint: TextView = activity.findViewById(R.id.auto_merge_hint)

    fun bind() {
        showPace(WorkPace.DEFAULT)
        toggle.isChecked = prefs.enabled()
        toggle.setOnCheckedChangeListener { _, on -> pick(on) }
    }

    /** Describes how auto merge works at [pace]; called whenever the Work pace section shows a pace. */
    fun showPace(pace: WorkPace) {
        hint.text = activity.getString(R.string.auto_merge_hint, activity.getString(orderOf(pace)))
    }

    private fun pick(on: Boolean) {
        if (on == prefs.enabled()) return
        prefs.setEnabled(on)
        if (!on) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !MergeNotices.allowed(activity)) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST)
        }
        AutoMergeJob.scheduleIfDue(activity, StateStore(activity).load().board)
    }

    companion object {
        const val PERMISSION_REQUEST = 79

        /** The "one at a time" / "back to back" part of the description for [pace]. */
        fun orderOf(pace: WorkPace): Int = when (pace) {
            WorkPace.SAVE -> R.string.auto_merge_order_save
            WorkPace.FAST -> R.string.auto_merge_order_fast
        }
    }
}
