package io.github.veganmarshmallow.taskboard.mergeall

import android.content.Context
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.merge.MergeDialogs
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllEnd
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllState
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllText
import io.github.veganmarshmallow.taskboard.data.AppGraph

/**
 * The notification when a Merge all run ends (#57): finished, cancelled, or which PR stopped it and
 * why (tapping it opens that PR). Posted through [MergeNotices].
 */
class MergeAllNotifier(private val context: Context) {
    private val text = MergeAllText(MergeDialogs(AppGraph.messages(context)))

    fun ended(state: MergeAllState) {
        val end = state.end ?: return
        val url = (end as? MergeAllEnd.Stopped)?.let { state.plan[it.at].pr.webUrl }
        MergeNotices.post(context, MergeNotices.Notice(CHANNEL, R.string.merge_all_channel, ID, context.getString(title(end)), text.result(state).orEmpty(), url))
    }

    private fun title(end: MergeAllEnd): Int = when (end) {
        MergeAllEnd.Done -> R.string.merge_all_done_title
        MergeAllEnd.Cancelled -> R.string.merge_all_cancelled_title
        is MergeAllEnd.Stopped -> R.string.merge_all_stopped_title
    }

    companion object {
        private const val CHANNEL = "merge_all"
        private const val ID = 57

        /** Android 13+ asks for the permission; earlier versions always allow it. */
        fun allowed(context: Context): Boolean = MergeNotices.allowed(context)
    }
}
