package io.github.veganmarshmallow.taskboard.widget

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import io.github.veganmarshmallow.taskboard.core.work.BoardJob
import io.github.veganmarshmallow.taskboard.core.work.RefreshPolicy
import io.github.veganmarshmallow.taskboard.core.work.RefreshRunner
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs [BoardJob]s on background threads. Each refresh is bounded by RefreshRunner's budget,
 * well inside JobScheduler's limits, and a hung refresh never blocks a newer one (separate threads).
 */
class BoardJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val job = BoardJob.fromExtras(params.extras::getString) ?: return false
        val app = applicationContext
        executor.execute {
            val retry = try {
                perform(job, WidgetRefresher(app))
            } catch (t: Throwable) {
                Log.w(TAG, "$job failed: ${t.javaClass.simpleName}")
                false
            }
            jobFinished(params, retry)
        }
        return true
    }

    /**
     * Stopped by the system (lost network, replaced by a newer refresh, time limit). The thread
     * finishes on its own within its budget; a superseded run's result is dropped. Only automatic
     * refreshes ask to run again.
     */
    override fun onStopJob(params: JobParameters): Boolean =
        (BoardJob.fromExtras(params.extras::getString) as? BoardJob.Refresh)?.trigger == RefreshTrigger.AUTOMATIC

    /** Returns whether the job should be retried later. */
    private fun perform(job: BoardJob, refresher: WidgetRefresher): Boolean = when (job) {
        is BoardJob.Refresh -> when (val result = refresher.refresh()) {
            is RefreshRunner.Result.Committed -> RefreshPolicy.shouldRetry(job.trigger, result.state)
            RefreshRunner.Result.Superseded -> false
        }
        is BoardJob.Approve -> {
            ApproveToast.show(applicationContext, job.issue, refresher.approve(job.issue))
            false
        }
        BoardJob.Sweep -> {
            refresher.sweep()
            false
        }
    }

    private companion object {
        const val TAG = "BoardJobService"

        /** Cached pool: a refresh stuck until its budget runs out never delays the next job. */
        val executor: ExecutorService = Executors.newCachedThreadPool { r ->
            Thread(r, "taskboard-job").apply { isDaemon = true }
        }
    }
}
