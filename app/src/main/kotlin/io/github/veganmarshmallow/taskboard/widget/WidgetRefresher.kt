package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import android.util.Log
import io.github.veganmarshmallow.taskboard.core.board.BoardLoader
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.error.Reachability
import io.github.veganmarshmallow.taskboard.core.merge.MergeLaneLoader
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.work.RefreshRunner
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.StateStore

/**
 * Blocking refresh, record-offline and sweep steps, run by [BoardJobService] on a background thread
 * (approving is [WidgetApprover]). Every refresh goes through [RefreshRunner], so it always ends in
 * the new board or an error, and the widget is re-rendered from that result.
 */
class WidgetRefresher(private val context: Context) {
    private val runner = AppGraph.refreshRunner(context)
    private val renderer = WidgetRenderer(context)

    fun refresh(): RefreshRunner.Result {
        val result = renderAfter(runner.run(onStarted = ::onStarted) { previous ->
            val next = BoardLoader(AppGraph.clock, mergeLanes = MergeLaneLoader.forRefresh(AppGraph.clock)).refresh(previous, AppGraph.apiOrNull(context))
            // "No network" only when the device really has none; otherwise GitHub is unreachable (#29).
            if (next.error == null) next else Reachability.refine(next, Connectivity.isOnline(context))
        })
        AppGraph.updates(context).checkInBackgroundIfDue() // queued on its own thread; never delays the board
        return result
    }

    /** Offline: records "No network" right away instead of waiting on a job that cannot run. Returns the state shown. */
    fun recordOffline(): WidgetState {
        val result = renderAfter(runner.run { it.withError(BoardError.Network, AppGraph.clock.instant()) })
        return (result as? RefreshRunner.Result.Committed)?.state ?: StateStore(context).load()
    }

    /** If a refresh died with its process, shows an error instead of "updating…". */
    fun recoverIfStale() {
        runner.recoverIfStale()?.let(::show)
    }

    /**
     * The sweep job: recovers a dead run, and otherwise just redraws what is stored, so a stray
     * "updating…" left in the launcher by any race is gone within a minute. Never saves a good board.
     * Returns the recovered (timeout) state, or null when no run had died.
     */
    fun sweep(): WidgetState? {
        val recovered = runner.recoverIfStale()
        draw { if (recovered != null) renderer.render(recovered) else renderer.renderStored() }
        return recovered
    }

    /**
     * Every run ends with a render, even a superseded one: its "updating…" may have reached the
     * launcher after the newer run's final render. The header then shows the tracker's truth.
     */
    private fun renderAfter(result: RefreshRunner.Result): RefreshRunner.Result {
        when (result) {
            is RefreshRunner.Result.Committed -> show(result.state)
            RefreshRunner.Result.Superseded -> draw { renderer.renderStored() }
        }
        return result
    }

    /**
     * Draws [state] on every widget. A drawing failure is logged, never thrown: the caller must still
     * plan the next retry, or the widget could stay on an error after the network is back (#29).
     */
    private fun show(state: WidgetState) = draw { renderer.render(state) }

    private fun draw(render: () -> Unit) {
        try {
            render()
        } catch (e: RuntimeException) {
            Log.w(TAG, "drawing the widget failed: ${e.javaClass.simpleName}")
        }
    }

    private fun onStarted() {
        BoardJobs.scheduleSweep(context)
        renderer.showUpdating()
    }

    private companion object {
        const val TAG = "WidgetRefresher"
    }
}
