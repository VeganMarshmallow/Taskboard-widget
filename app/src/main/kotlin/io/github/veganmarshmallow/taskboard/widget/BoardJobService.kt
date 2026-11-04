package io.github.veganmarshmallow.taskboard.widget

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.util.Log
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllMachine
import io.github.veganmarshmallow.taskboard.core.work.BoardJob
import io.github.veganmarshmallow.taskboard.core.work.RefreshRunner
import io.github.veganmarshmallow.taskboard.mergeall.MergeAllJob
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
            val next = try {
                perform(job, app)
            } catch (t: Throwable) {
                Log.w(TAG, "$job failed: ${t.javaClass.simpleName}")
                if (job is BoardJob.Refresh) RetryScheduler.afterCrash(app, job) // keep the retry chain alive (#29)
                if (job is BoardJob.MergeAll) BoardJob.MergeAll(MergeAllMachine.POLL) else null
            }
            // Never JobScheduler's own retry: failed refreshes plan theirs ([RetryScheduler]), and a
            // Merge all run schedules its next job itself, once this one has finished.
            jobFinished(params, false)
            next?.let { BoardJobs.schedule(app, it) }
        }
        return true
    }

    /**
     * Stopped by the system (lost network, replaced by a newer refresh, time limit). The thread
     * finishes on its own within its budget; a superseded run's result is dropped. Background
     * refreshes ask to run again, and so does Merge all (its thread also schedules the next job;
     * whichever comes last replaces the other).
     */
    override fun onStopJob(params: JobParameters): Boolean = when (val job = BoardJob.fromExtras(params.extras::getString)) {
        is BoardJob.Refresh -> !job.urgent
        is BoardJob.MergeAll -> true
        else -> false
    }

    /** Does [job]; returns the job to schedule once it has finished (Merge all's next step), if any. */
    private fun perform(job: BoardJob, app: Context): BoardJob? {
        when (job) {
            is BoardJob.Refresh -> {
                when (val result = WidgetRefresher(app).refresh()) {
                    is RefreshRunner.Result.Committed -> RetryScheduler.afterRefresh(app, result.state.error, job)
                    RefreshRunner.Result.Superseded -> Unit // the newer run plans its own retry
                }
                MergeAllJob.ensureScheduled(app)
            }
            is BoardJob.Approve -> WidgetApprover(app).approveAndToast(job.issue)
            BoardJob.Sweep -> WidgetRefresher(app).sweep()?.let { RetryScheduler.afterRefresh(app, it.error, finished = null) }
            is BoardJob.MergeAll -> return MergeAllJob(app).run()?.let { BoardJob.MergeAll(it) }
        }
        return null
    }

    private companion object {
        const val TAG = "BoardJobService"

        /** Cached pool: a refresh stuck until its budget runs out never delays the next job. */
        val executor: ExecutorService = Executors.newCachedThreadPool { r ->
            Thread(r, "taskboard-job").apply { isDaemon = true }
        }
    }
}
