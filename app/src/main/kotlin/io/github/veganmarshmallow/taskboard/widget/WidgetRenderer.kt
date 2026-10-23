package io.github.veganmarshmallow.taskboard.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.work.SerialRender
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.data.TabStore

/**
 * Pushes [WidgetViews] to every widget instance and tells the list to reload its rows.
 *
 * Every push is a full update, and every one goes through [GATE] (task #33). The selected tab and
 * the "updating" flag are read inside the gate, right before the push, so the last update the
 * launcher gets always shows the latest tap. The list re-reads the tab by itself, so before this a
 * redraw from another thread that read the tab just before a tap, and pushed just after it, left
 * the old tab highlighted above the new tab's list until the next tap.
 */
class WidgetRenderer(private val context: Context) {
    private val manager = AppWidgetManager.getInstance(context)
    private val views = WidgetViews(context)
    private val tabs = TabStore(context)

    fun widgetIds(): IntArray = manager.getAppWidgetIds(ComponentName(context, BoardWidgetProvider::class.java))

    /** [updating]: null reads it from the refresh tracker (inside the gate, so it is current). */
    fun render(state: WidgetState, ids: IntArray = widgetIds(), updating: Boolean? = null) {
        GATE.run({ build(state, ids, updating ?: AppGraph.refreshTracker(context).isUpdating()) }) { built ->
            built.forEach { (id, remote) -> manager.updateAppWidget(id, remote) }
            if (ids.isNotEmpty()) manager.notifyAppWidgetViewDataChanged(ids, R.id.board_list)
        }
    }

    /**
     * Shows "updating…" in the header while a refresh runs, without reloading the list. A full
     * update of the stored state, not a partial one: a partial update is merged into whatever the
     * system cached, and launchers differ in how they apply it, so the tabs could come out of step.
     */
    fun showUpdating(ids: IntArray = widgetIds()) {
        GATE.run({ build(StateStore(context).load(), ids, updating = true) }) { built ->
            built.forEach { (id, remote) -> manager.updateAppWidget(id, remote) }
        }
    }

    private fun build(state: WidgetState, ids: IntArray, updating: Boolean): List<Pair<Int, RemoteViews>> =
        ids.map { id -> id to views.build(state, id, tabs.selected(id, state.board), updating) }

    private companion object {
        /** One per process: refresh jobs, the sweep, the update dot and tab taps all draw through it. */
        val GATE = SerialRender()
    }
}
