package io.github.veganmarshmallow.taskboard.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.veganmarshmallow.taskboard.core.work.BoardJob
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger

/**
 * The widget's refresh button, confirmed approvals and tab taps. Refresh and approve only schedule
 * jobs; a tab tap is quick local work, done in place ([TabTap]).
 */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WidgetIntents.ACTION_NOOP -> Unit // a tap on the header: deliberately nothing (task #16)
            WidgetIntents.ACTION_REFRESH -> BoardJobs.refresh(context, RefreshTrigger.MANUAL)
            WidgetIntents.ACTION_SELECT_TAB -> TabTap.handle(context.applicationContext, intent)
            WidgetIntents.ACTION_APPROVE -> {
                val issue = intent.getIntExtra(WidgetIntents.EXTRA_ISSUE, 0)
                if (issue in 1..BoardJob.MAX_ISSUE) BoardJobs.approve(context, issue)
            }
        }
    }
}
