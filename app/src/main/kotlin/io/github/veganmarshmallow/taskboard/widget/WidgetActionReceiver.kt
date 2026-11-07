package io.github.veganmarshmallow.taskboard.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.veganmarshmallow.taskboard.core.work.BoardJob
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger
import io.github.veganmarshmallow.taskboard.data.FilterStore
import io.github.veganmarshmallow.taskboard.mergeall.MergeAllJob

/**
 * The widget's refresh button, confirmed approvals, tab taps, Merge all's Cancel, OK (#57) and Reset (#78) and the filter's Clear (#59).
 * Refresh and approve only schedule jobs; a tab tap or Clear is quick local work, done in place ([TabTap]).
 */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WidgetIntents.ACTION_NOOP -> Unit // a tap on the header: deliberately nothing (task #16)
            WidgetIntents.ACTION_REFRESH -> BoardJobs.refresh(context, RefreshTrigger.MANUAL)
            WidgetIntents.ACTION_SELECT_TAB -> TabTap.handle(context.applicationContext, intent)
            WidgetIntents.ACTION_MERGE_ALL_CANCEL -> MergeAllJob.cancel(context)
            WidgetIntents.ACTION_MERGE_ALL_DISMISS -> MergeAllJob.dismiss(context)
            WidgetIntents.ACTION_MERGE_ALL_RESET -> MergeAllJob.reset(context)
            WidgetIntents.ACTION_CLEAR_FILTER -> {
                FilterStore(context).clear()
                StateRedraw.redraw(context)
            }
            WidgetIntents.ACTION_APPROVE -> {
                val issue = intent.getIntExtra(WidgetIntents.EXTRA_ISSUE, 0)
                if (issue in 1..BoardJob.MAX_ISSUE) BoardJobs.approve(context, issue)
            }
        }
    }
}
