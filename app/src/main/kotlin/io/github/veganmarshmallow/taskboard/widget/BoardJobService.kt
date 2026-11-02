package io.github.veganmarshmallow.taskboard.widget

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.util.Log
import io.github.veganmarshmallow.taskboard.core.work.BoardJob
import io.github.veganmarshmallow.taskboard.core.work.RefreshRunner
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
                perform(job, app)
            } catch (t: Throwable) {
                Log.w(TAG, "$job failed: ${t.javaClass.simpleName}")
                if (job is BoardJob.Refresh) RetryScheduler.afterCrash(app, job) // keep the retry chain alive (#29)
                false
            }
            jobFinished(params, retry)
        }
        return true
    }

    /**
     * Stopped by the system (lost network, replaced by a newer refresh, time limit). The thread
     * finishes on its own within its budget; a superseded run's result is dropped. Background
     * refreshes ask to run again.
     */
    override fun onStopJob(params: JobParameters): Boolean =
        (BoardJob.fromExtras(params.extras::getString) as? BoardJob.Refresh)?.let { !it.urgent } ?: false

    /**
     * Returns whether JobScheduler should retry the job. Always false: failed refreshes schedule
     * their own retry with the app's backoff ([RetryScheduler]), not the scheduler's.
     */
    private fun perform(job: BoardJob, app: Context): Boolean {
        when (job) {
            is BoardJob.Refresh -> when (val result = WidgetRefresher(app).refresh()) {
                is RefreshRunner.Result.Committed -> RetryScheduler.afterRefresh(app, result.state.error, job)
                RefreshRunner.Result.Superseded -> Unit // the newer run plans its own retry
            }
            is BoardJob.Approve -> WidgetApprover(app).approveAndToast(job.issue)
            BoardJob.Sweep -> WidgetRefresher(app).sweep()?.let { RetryScheduler.afterRefresh(app, it.error, finished = null) }
        }
        return false
    }

    private companion object {
        const val TAG = "BoardJobService"

        /** Cached pool: a refresh stuck until its budget runs out never delays the next job. */
        val executor: ExecutorService = Executors.newCachedThreadPool { r ->
            Thread(r, "taskboard-job").apply { isDaemon = true }
        }
    }
}
