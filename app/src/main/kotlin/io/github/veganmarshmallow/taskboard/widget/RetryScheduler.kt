package io.github.veganmarshmallow.taskboard.widget

import android.app.job.JobScheduler
import android.content.Context
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.work.BoardJob
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger
import io.github.veganmarshmallow.taskboard.data.AppGraph

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
        if (otherRefreshPending(app, finished)) return // it will plan the next retry when it ends
        BoardJobs.schedule(app, BoardJob.Refresh(RefreshTrigger.RETRY, delay))
    }

    /**
     * Whether a different refresh is already waiting (e.g. a tap made while this one ran). Replacing
     * it would lose that refresh, since all refreshes share one job id. An older retry is replaced.
     */
    private fun otherRefreshPending(context: Context, finished: BoardJob.Refresh?): Boolean {
        val info = try {
            context.getSystemService(JobScheduler::class.java)?.getPendingJob(BoardJob.REFRESH_ID)
        } catch (e: RuntimeException) {
            null
        } ?: return false
        val pending = BoardJob.fromExtras(info.extras::getString) as? BoardJob.Refresh ?: return false
        return pending.trigger != RefreshTrigger.RETRY && pending != finished
    }
}
