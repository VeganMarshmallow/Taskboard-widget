package io.github.veganmarshmallow.taskboard.widget

import android.app.job.JobScheduler
import android.content.Context
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.work.BoardJob
import io.github.veganmarshmallow.taskboard.core.work.RefreshPolicy
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.StateStore

/**
 * Auto-recovery (#29): after every refresh outcome, asks the core [RetryPlanner] whether and when
 * to retry, and schedules that retry as a persisted refresh job that also waits for any network
 * with internet (a VPN included, no validation). So the widget recovers by itself once the network
 * is back, even after a reboot, with the backoff growing while it stays broken.
 */
object RetryScheduler {
    /**
     * [error]: how the refresh ended (null = success). [finished]: the refresh job that produced it,
     * or null when it came from elsewhere (an offline tap, a dead run cleaned up by the sweep).
     */
    fun afterRefresh(context: Context, error: BoardError?, finished: BoardJob.Refresh?) {
        val app = context.applicationContext
        val delay = AppGraph.retryPlanner(app).afterRefresh(error) ?: return
        if (RefreshPolicy.retryYieldsTo(pendingRefresh(app), finished)) return // it plans the next retry when it ends
        BoardJobs.schedule(app, BoardJob.Refresh(RefreshTrigger.RETRY, delay))
    }

    /**
     * A refresh job that threw (e.g. drawing the widget failed) still plans its retry from the stored
     * state, so one bad run never ends the chain of retries. Never throws.
     */
    fun afterCrash(context: Context, job: BoardJob.Refresh) {
        try {
            afterRefresh(context, StateStore(context.applicationContext).load().error, job)
        } catch (e: RuntimeException) {
            // Nothing more to do; the widget's periodic update still refreshes.
        }
    }

    /** The last widget was removed: no more background retries, and the backoff starts over next time. */
    fun stop(context: Context) {
        val app = context.applicationContext
        AppGraph.retryPlanner(app).reset()
        try {
            app.getSystemService(JobScheduler::class.java)?.cancel(BoardJob.REFRESH_ID)
        } catch (e: RuntimeException) {
            // The pending refresh then runs once more and, with no widget left, changes nothing visible.
        }
    }

    /** The refresh job waiting (or running) right now, if any. */
    private fun pendingRefresh(context: Context): BoardJob.Refresh? {
        val info = try {
            context.getSystemService(JobScheduler::class.java)?.getPendingJob(BoardJob.REFRESH_ID)
        } catch (e: RuntimeException) {
            null
        } ?: return null
        return BoardJob.fromExtras(info.extras::getString) as? BoardJob.Refresh
    }
}
