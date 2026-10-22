package io.github.veganmarshmallow.taskboard.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.veganmarshmallow.taskboard.core.work.BoardJob
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger

/** The widget's refresh button and confirmed approvals. Only schedules jobs; no work runs in the receiver. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WidgetIntents.ACTION_REFRESH -> BoardJobs.refresh(context, RefreshTrigger.MANUAL)
            WidgetIntents.ACTION_APPROVE -> {
                val issue = intent.getIntExtra(WidgetIntents.EXTRA_ISSUE, 0)
                if (issue in 1..BoardJob.MAX_ISSUE) BoardJobs.approve(context, issue)
            }
        }
    }
}
