package io.github.veganmarshmallow.taskboard.mergeall

import android.app.job.JobScheduler
import android.content.Context
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllControl
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllHealth
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllMachine
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllRunner
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllState
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStop
import io.github.veganmarshmallow.taskboard.core.mergeall.PlannedMerge
import io.github.veganmarshmallow.taskboard.core.mergeall.PullsMergeAllGitHub
import io.github.veganmarshmallow.taskboard.core.work.BoardJob
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.WorkPaceStore
import io.github.veganmarshmallow.taskboard.data.TestOverrides
import io.github.veganmarshmallow.taskboard.widget.BoardJobs
import io.github.veganmarshmallow.taskboard.widget.Connectivity
import io.github.veganmarshmallow.taskboard.widget.StateRedraw
import java.time.Duration
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The Android side of a Merge all run (#57): starts it, and runs it in [BoardJob.MergeAll] jobs
 * ([io.github.veganmarshmallow.taskboard.widget.BoardJobService]). Each job takes the saved run as
 * far as [MergeAllRunner] goes and returns when to run again; the widget redraws after every step,
 * and the end posts a notification and refreshes the board.
 *
 * #78: each job checks the network before a step (the job also runs past its deadline without one);
 * Cancel between steps ends the run at once; Reset forgets a run that won't stop; a stalled run is
 * scheduled again by [ensureScheduled] on the next refresh or app open.
 */
class MergeAllJob(private val context: Context) {
    private val app = context.applicationContext
    private val store = AppGraph.mergeAllStore(app)

    /** Runs the saved run; the delay before the next job, or null when there is nothing left to do. */
    fun run(): Duration? = LOCK.withLock {
        val saved = store.load()?.takeIf { it.running } ?: return null
        val api = AppGraph.apiOrNull(app)
        if (api == null) {
            ended(saved.stopped(MergeAllStop(MergeAllStop.Kind.NO_TOKEN), AppGraph.clock.instant()).also(store::save))
            return null
        }
        val pace = WorkPaceStore(app).cached() // #83: Faster merges green PRs back to back
        val runner = MergeAllRunner(MergeAllMachine(PullsMergeAllGitHub(api, pace), pace = pace), store, onStep = { StateRedraw.redraw(app) }, online = { online(app) })
        val result = runner.run()
        result.state?.takeUnless { it.running }?.let { ended(it) }
        result.next
    }

    private fun ended(state: MergeAllState) = ended(app, state)

    companion object {
        /**
         * One run at a time per process, even if JobScheduler starts a second job while one runs.
         * Held only while steps run, so Cancel can tell a run waiting between polls ([cancel]).
         */
        private val LOCK = ReentrantLock()

        private fun ended(app: Context, state: MergeAllState) {
            StateRedraw.redraw(app)
            MergeAllNotifier(app).ended(state)
            BoardJobs.refresh(app, RefreshTrigger.MANUAL)
        }

        private fun online(app: Context): Boolean = TestOverrides.online?.invoke() ?: Connectivity.isOnline(app)

        private fun control(app: Context) = MergeAllControl(AppGraph.mergeAllStore(app), AppGraph.clock::instant)

        private fun cancelJob(app: Context) {
            app.getSystemService(JobScheduler::class.java)?.cancel(BoardJob.MERGE_ALL_ID)
        }

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
         * A run that is going on but has no job (the app was force-stopped, or a job was lost), or
         * whose job hasn't polled for a while (held back by Android, #78), gets a job now. Called on
         * every refresh and when the app opens.
         */
        fun ensureScheduled(context: Context) {
            val app = context.applicationContext
            val state = AppGraph.mergeAllStore(app).load()?.takeIf { it.running } ?: return
            val scheduler = app.getSystemService(JobScheduler::class.java) ?: return
            val stale = MergeAllHealth.stale(state, AppGraph.clock.instant())
            if (stale || scheduler.getPendingJob(BoardJob.MERGE_ALL_ID) == null) BoardJobs.schedule(app, BoardJob.MergeAll())
        }

        /**
         * The Cancel button. Between steps (no job step running, not at the merge) the run ends at
         * once (#78); otherwise it stops right after the step in flight (a request already sent finishes).
         */
        fun cancel(context: Context) {
            val app = context.applicationContext
            val idle = LOCK.tryLock()
            val cancelled = try {
                control(app).cancel(idle)
            } finally {
                if (idle) LOCK.unlock()
            }
            when {
                cancelled != null -> { cancelJob(app); ended(app, cancelled) }
                // At its merge and between steps: the job re-reads the PR and stops, without waiting a poll.
                idle && AppGraph.mergeAllStore(app).load()?.running == true -> BoardJobs.schedule(app, BoardJob.MergeAll())
            }
            StateRedraw.redraw(app)
        }

        /** The Reset button (#78): forgets a run that won't stop, and its job. */
        fun reset(context: Context) {
            val app = context.applicationContext
            cancelJob(app)
            control(app).reset()
            StateRedraw.redraw(app)
        }

        /** The OK button on an ended run's line: the line goes away. */
        fun dismiss(context: Context) {
            val store = AppGraph.mergeAllStore(context)
            if (store.load()?.running != true) store.clear()
            StateRedraw.redraw(context.applicationContext)
        }
    }
}
