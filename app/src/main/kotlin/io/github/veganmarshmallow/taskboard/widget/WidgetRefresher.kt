package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import io.github.veganmarshmallow.taskboard.core.board.BoardLoader
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.StateStore

/** Blocking refresh/approve steps. Always called on [BackgroundWork]'s thread. */
class WidgetRefresher(private val context: Context) {
    private val store = StateStore(context)
    private val renderer = WidgetRenderer(context)

    fun refresh() {
        renderer.showUpdating()
        val state = BoardLoader(AppGraph.clock).refresh(store.load(), AppGraph.apiOrNull(context))
        store.save(state)
        renderer.render(state)
    }

    /** Adds `ready-for-claude`, then refreshes. Returns the failure, if any, for the caller to report. */
    fun approve(issue: Int): Outcome<Unit> {
        val result = AppGraph.apiOrNull(context)?.approve(issue) ?: Outcome.Failure(BoardError.NoToken)
        refresh()
        return result
    }
}
