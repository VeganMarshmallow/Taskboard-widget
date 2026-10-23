package io.github.veganmarshmallow.taskboard.widget

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.work.BoardJob
import io.github.veganmarshmallow.taskboard.core.work.RefreshPolicy
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger
import io.github.veganmarshmallow.taskboard.data.AppGraph

/**
 * The only way widget work is started: everything runs as a JobScheduler job in [BoardJobService],
 * never under a broadcast's goAsync() (which Android cuts off after about 10 seconds).
 * Safe to call on the main thread: it only reads small local state and schedules.
 */
object BoardJobs {
    private const val TAG = "BoardJobs"
    private const val BACKOFF_MS = 30_000L

    /**
     * Asks for a refresh. Manual ones always go ahead, replacing a running (possibly stuck) refresh,
     * and restart the retry backoff (someone is waiting). Offline, a manual refresh shows
     * "No network" at once and leaves a retry that runs once the network is back; an automatic one
     * simply waits for the network.
     */
    fun refresh(context: Context, trigger: RefreshTrigger) {
        val app = context.applicationContext
        if (!RefreshPolicy.shouldSchedule(trigger, AppGraph.refreshTracker(app).status())) return
        if (trigger == RefreshTrigger.MANUAL) AppGraph.retryPlanner(app).reset()
        if (trigger == RefreshTrigger.MANUAL && !Connectivity.isOnline(app)) {
            val state = WidgetRefresher(app).recordOffline()
            RetryScheduler.afterRefresh(app, state.error, finished = null)
            return
        }
        schedule(app, BoardJob.Refresh(trigger))
    }

    fun approve(context: Context, issue: Int) {
        val app = context.applicationContext
        if (!Connectivity.isOnline(app)) {
            ApproveToast.show(app, issue, Outcome.Failure(BoardError.Network))
            return
        }
        schedule(app, BoardJob.Approve(issue))
    }

    /** Turns a dead refresh's "updating…" into an error, even if this process dies too. */
    fun scheduleSweep(context: Context) {
        schedule(context.applicationContext, BoardJob.Sweep)
    }

    /** Schedules [job], expedited when urgent, falling back to a regular job (e.g. out of expedited quota). */
    fun schedule(context: Context, job: BoardJob): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        val attempts = if (job.urgent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(true, false) else listOf(false)
        for (expedited in attempts) {
            try {
                if (scheduler.schedule(jobInfo(context, job, expedited)) == JobScheduler.RESULT_SUCCESS) return true
            } catch (e: RuntimeException) {
                Log.w(TAG, "scheduling $job (expedited=$expedited) failed: ${e.javaClass.simpleName}")
            }
        }
        return false
    }

    fun jobInfo(context: Context, job: BoardJob, expedited: Boolean): JobInfo {
        val extras = PersistableBundle().apply { job.toExtras().forEach { (k, v) -> putString(k, v) } }
        val builder = JobInfo.Builder(job.jobId, ComponentName(context, BoardJobService::class.java)).setExtras(extras)
        if (job.needsNetwork) requireNetwork(builder)
        if (!job.minLatency.isZero) builder.setMinimumLatency(job.minLatency.toMillis())
        if (job.persisted) builder.setPersisted(true)
        if (!expedited) job.runAnywayAfter?.let { builder.setOverrideDeadline(it.toMillis()) }
        if (job is BoardJob.Refresh) builder.setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
        if (expedited && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setExpedited(true)
        return builder.build()
    }

    /**
     * NETWORK_TYPE_ANY also requires a *validated* network from Android 9. Ask only for a network
     * with internet, a VPN included: validation over a VPN or hotspot can lag or never come, and
     * a refresh or retry must not wait for it (#29; see BoardJob).
     */
    private fun requireNetwork(builder: JobInfo.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setRequiredNetwork(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) // the owner's phone runs a VPN
                    .build(),
            )
        } else {
            builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        }
    }
}
