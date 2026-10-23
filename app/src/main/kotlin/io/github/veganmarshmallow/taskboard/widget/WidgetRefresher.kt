package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import io.github.veganmarshmallow.taskboard.core.board.BoardLoader
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.work.RefreshRunner
import io.github.veganmarshmallow.taskboard.core.work.RefreshTrigger
import io.github.veganmarshmallow.taskboard.core.work.TimeLimit
import io.github.veganmarshmallow.taskboard.core.work.toOutcome
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.update.UpdateController
import java.time.Duration

/**
 * Blocking refresh/approve steps, run by [BoardJobService] on a background thread. Every refresh
 * goes through [RefreshRunner], so it always ends in the new board or an error, and the widget
 * is re-rendered from that result.
 */
class WidgetRefresher(private val context: Context) {
    private val runner = AppGraph.refreshRunner(context)
    private val renderer = WidgetRenderer(context)

    fun refresh(): RefreshRunner.Result {
        val result = renderAfter(runner.run(onStarted = ::onStarted) { previous ->
            BoardLoader(AppGraph.clock).refresh(previous, AppGraph.apiOrNull(context))
        })
        UpdateController.checkInBackgroundIfDue(context) // queued on its own thread; never delays the board
        return result
    }

    /** Offline: records "No network" right away instead of waiting on a job that cannot run. */
    fun recordOffline() {
        renderAfter(runner.run { it.withError(BoardError.Network, AppGraph.clock.instant()) })
    }

    /** If a refresh died with its process, shows an error instead of "updating…". */
    fun recoverIfStale() {
        runner.recoverIfStale()?.let { renderer.render(it) }
    }

    /**
     * The sweep job: recovers a dead run, and otherwise just redraws what is stored, so a stray
     * "updating…" left in the launcher by any race is gone within a minute. Never saves a good board.
     */
    fun sweep() {
        renderer.render(runner.recoverIfStale() ?: StateStore(context).load())
    }

    /**
     * Every run ends with a render, even a superseded one: its "updating…" may have reached the
     * launcher after the newer run's final render. The header then shows the tracker's truth.
     */
    private fun renderAfter(result: RefreshRunner.Result): RefreshRunner.Result {
        val state = (result as? RefreshRunner.Result.Committed)?.state ?: StateStore(context).load()
        renderer.render(state)
        return result
    }

    /** Adds `ready-for-claude`, then asks for a refresh. Returns the failure, if any, for the caller to report. */
    fun approve(issue: Int): Outcome<Unit> {
        val api = AppGraph.apiOrNull(context) ?: return Outcome.Failure(BoardError.NoToken)
        val result = TimeLimit().run(APPROVE_BUDGET) { api.approve(issue) }.toOutcome()
        BoardJobs.refresh(context, RefreshTrigger.MANUAL)
        return result
    }

    private fun onStarted() {
        BoardJobs.scheduleSweep(context)
        renderer.showUpdating()
    }

    private companion object {
        val APPROVE_BUDGET: Duration = Duration.ofSeconds(30)
    }
}
