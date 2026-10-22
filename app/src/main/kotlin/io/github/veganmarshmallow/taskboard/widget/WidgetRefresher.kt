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
        val result = runner.run(onStarted = ::onStarted) { previous ->
            BoardLoader(AppGraph.clock).refresh(previous, AppGraph.apiOrNull(context))
        }
        if (result is RefreshRunner.Result.Committed) renderer.render(result.state)
        return result
    }

    /** Offline: records "No network" right away instead of waiting on a job that cannot run. */
    fun recordOffline() {
        val result = runner.run { it.withError(BoardError.Network, AppGraph.clock.instant()) }
        if (result is RefreshRunner.Result.Committed) renderer.render(result.state)
    }

    /** If a refresh died with its process, shows an error instead of "updating…". */
    fun recoverIfStale() {
        runner.recoverIfStale()?.let { renderer.render(it) }
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
