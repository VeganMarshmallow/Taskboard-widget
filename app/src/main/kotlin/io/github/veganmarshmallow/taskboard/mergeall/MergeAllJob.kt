package io.github.veganmarshmallow.taskboard.mergeall

import android.app.job.JobScheduler
import android.content.Context
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllMachine
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllRunner
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllState
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStop
import io.github.veganmarshmallow.taskboard.core.mergeall.PlannedMerge
import io.github.veganmarshmallow.taskboard.core.mergeall.PullsMergeAllGitHub
import io.github.veganmarshmallow.taskboard.core.work.BoardJob
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.widget.BoardJobs
import io.github.veganmarshmallow.taskboard.widget.StateRedraw
import java.time.Duration
import java.util.UUID

/**
 * The Android side of a Merge all run (#57): starts it, and runs it in [BoardJob.MergeAll] jobs
 * ([io.github.veganmarshmallow.taskboard.widget.BoardJobService]). Each job takes the saved run as
 * far as [MergeAllRunner] goes and returns when to run again; the widget redraws after every step,
 * and the end posts a notification and refreshes the board.
 */
class MergeAllJob(private val context: Context) {
    private val app = context.applicationContext
    private val store = AppGraph.mergeAllStore(app)

    /** Runs the saved run; the delay before the next job, or null when there is nothing left to do. */
    fun run(): Duration? = synchronized(LOCK) {
        val saved = store.load()?.takeIf { it.running } ?: return null
        val api = AppGraph.apiOrNull(app)
        if (api == null) {
            ended(saved.stopped(MergeAllStop(MergeAllStop.Kind.NO_TOKEN), AppGraph.clock.instant()).also(store::save))
            return null
        }
        val runner = MergeAllRunner(MergeAllMachine(PullsMergeAllGitHub(api)), store, onStep = { StateRedraw.redraw(app) })
        val result = runner.run()
        result.state?.takeUnless { it.running }?.let(::ended)
        result.next
    }

    private fun ended(state: MergeAllState) {
        StateRedraw.redraw(app)
        MergeAllNotifier(app).ended(state)
        BoardJobs.refresh(app, RefreshTrigger.MANUAL)
    }

    companion object {
        /** One run at a time per process, even if JobScheduler starts a second job while one runs. */
        private val LOCK = Any()

        /** Saves a new run of [plan] and schedules its job. False when a run is already going on. */
        fun start(context: Context, plan: List<PlannedMerge>): Boolean {
            val app = context.applicationContext
            val store = AppGraph.mergeAllStore(app)
            if (plan.isEmpty() || store.load()?.running == true) return false
            store.save(MergeAllState.start(UUID.randomUUID().toString(), plan, AppGraph.clock.instant()))
            BoardJobs.schedule(app, BoardJob.MergeAll())
            StateRedraw.redraw(app)
            return true
        }

        /**
         * A run that is going on but has no job (the app was force-stopped, or a job was lost)
         * gets one again. Called after every refresh.
         */
        fun ensureScheduled(context: Context) {
            val app = context.applicationContext
            if (AppGraph.mergeAllStore(app).load()?.running != true) return
            val scheduler = app.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(BoardJob.MERGE_ALL_ID) == null) BoardJobs.schedule(app, BoardJob.MergeAll())
        }

        /** The Cancel button: the run stops before its next step (a request already sent finishes). */
        fun cancel(context: Context) {
            val store = AppGraph.mergeAllStore(context)
            store.load()?.takeIf { it.running }?.let { store.requestCancel(it.runId) }
            StateRedraw.redraw(context.applicationContext)
        }

        /** The OK button on an ended run's line: the line goes away. */
        fun dismiss(context: Context) {
            val store = AppGraph.mergeAllStore(context)
            if (store.load()?.running != true) store.clear()
            StateRedraw.redraw(context.applicationContext)
        }
    }
}
