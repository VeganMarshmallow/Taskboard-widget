package io.github.veganmarshmallow.taskboard.automerge

import android.content.Context
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.automerge.AutoMergeGate
import io.github.veganmarshmallow.taskboard.core.automerge.AutoMergeNotices
import io.github.veganmarshmallow.taskboard.core.automerge.AutoMergeOutcome
import io.github.veganmarshmallow.taskboard.core.automerge.AutoMergeText
import io.github.veganmarshmallow.taskboard.core.automerge.AutoMerger
import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.merge.MergeDialogs
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllPlanning
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllText
import io.github.veganmarshmallow.taskboard.core.mergeall.PullsMergeAllGitHub
import io.github.veganmarshmallow.taskboard.core.work.BoardJob
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.AutoMergePrefs
import io.github.veganmarshmallow.taskboard.mergeall.MergeNotices
import io.github.veganmarshmallow.taskboard.widget.BoardJobs
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The Android side of auto merge (#79): one [BoardJob.AutoMerge] poll ([AutoMerger], with the
 * owner's token), started after a refresh found a PR to merge next ([scheduleIfDue]). A merge, or a
 * new failure, posts a notification; a merge also refreshes the board. A Merge all run going on
 * makes the poll wait, so the two never merge against each other.
 */
class AutoMergeJob(context: Context) {
    private val app = context.applicationContext
    private val prefs = AutoMergePrefs(app)

    fun run(): AutoMergeOutcome = LOCK.withLock {
        val api = AppGraph.apiOrNull(app) ?: return AutoMergeOutcome.Skipped(AutoMergeOutcome.Skip.CANT_CHECK)
        val merger = AutoMerger({ MergeAllPlanning(api).candidates() }, PullsMergeAllGitHub(api))
        merger.poll(prefs.enabled(), mergeAllRunning(app)).also(::report)
    }

    private fun report(outcome: AutoMergeOutcome) {
        val last = prefs.lastFailure()
        if (AutoMergeNotices.shouldNotify(last, outcome)) post(outcome)
        prefs.setLastFailure(AutoMergeNotices.remembered(last, outcome))
        if (outcome is AutoMergeOutcome.Merged) BoardJobs.refresh(app, RefreshTrigger.AUTOMATIC)
    }

    private fun post(outcome: AutoMergeOutcome) {
        val (title, body) = AutoMergeText(MergeAllText(MergeDialogs(AppGraph.messages(app)))).notice(outcome) ?: return
        val merge = when (outcome) {
            is AutoMergeOutcome.Merged -> outcome.merge
            is AutoMergeOutcome.Failed -> outcome.merge
            is AutoMergeOutcome.Skipped -> return
        }
        // One notification per PR (its tag), so merging several in a row shows each.
        MergeNotices.post(app, MergeNotices.Notice(CHANNEL, R.string.auto_merge_channel, ID, title, body, merge.pr.webUrl, merge.pr.toString()))
    }

    companion object {
        private const val CHANNEL = "auto_merge"
        private const val ID = 79

        /** One poll at a time per process, even if JobScheduler starts a second job while one runs. */
        private val LOCK = ReentrantLock()

        private fun mergeAllRunning(context: Context): Boolean = AppGraph.mergeAllStore(context).load()?.running == true

        /**
         * After a refresh (or when the setting is turned on): schedules a poll when auto merge is
         * on, no Merge all run is going on, and [board] has a PR to merge next. Otherwise nothing.
         */
        fun scheduleIfDue(context: Context, board: Board?) {
            val app = context.applicationContext
            if (AutoMergeGate.shouldPoll(AutoMergePrefs(app).enabled(), mergeAllRunning(app), board)) BoardJobs.schedule(app, BoardJob.AutoMerge)
        }
    }
}
