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
 *
 * On Android 12+ the list's rows are part of each update too ([ListBinding], task #40), so an
 * update is the whole widget, and `updateAppWidget` stays a plain synchronous call: with a
 * RemoteViewsService list, Android 15/16 would send it later from a thread of its own, outside
 * this gate.
 */
class WidgetRenderer(private val context: Context) {
    private val manager = AppWidgetManager.getInstance(context)
    private val views = WidgetViews(context)
    private val tabs = TabStore(context)

    fun widgetIds(): IntArray = manager.getAppWidgetIds(ComponentName(context, BoardWidgetProvider::class.java))

    /**
     * Draws [state]. For tests only: production code always draws with [renderStored], which reads
     * the state inside the gate, so an older state handed in from outside can never land last (#37).
     * [updating]: null reads it from the refresh tracker (inside the gate, so it is current).
     */
    fun render(state: WidgetState, ids: IntArray = widgetIds(), updating: Boolean? = null) =
        push(ids, notifyList = true, updating = updating) { state }

    /**
     * Draws the stored state (with a failed save's error, see [StateStore]), read inside the gate.
     * A state loaded before waiting for the gate could be older than a refresh that saved and drew
     * meanwhile; drawn last, it would leave the header (tab counts, time) behind the list, which
     * re-reads the store itself, or bring back an older run's board over a newer one.
     */
    fun renderStored(ids: IntArray = widgetIds()) = push(ids, notifyList = true) { StateStore(context).load() }

    /**
     * Shows "updating…" in the header while a refresh runs, without reloading the list. A full
     * update of the stored state, not a partial one: a partial update is merged into whatever the
     * system cached, and launchers differ in how they apply it, so the tabs could come out of step.
     * The flag is read from the tracker like any render, so a late call never brings back
     * "updating…" after the run has already ended.
     */
    fun showUpdating(ids: IntArray = widgetIds()) = push(ids, notifyList = false) { StateStore(context).load() }

    private fun push(ids: IntArray, notifyList: Boolean, updating: Boolean? = null, load: () -> WidgetState) {
        GATE.run({ build(load(), ids, updating ?: AppGraph.refreshTracker(context).isUpdating()) }) { built ->
            built.forEach { (id, remote) -> manager.updateAppWidget(id, remote) }
            // Android 12+: the rows came inside the update. Never notify there: from Android 15/16 a
            // notify makes AppWidgetManager re-send the system's previous views, old tab and all (#40).
            if (notifyList && ids.isNotEmpty() && !ListBinding.embedsRows()) {
                manager.notifyAppWidgetViewDataChanged(ids, R.id.board_list)
            }
        }
    }

    private fun build(state: WidgetState, ids: IntArray, updating: Boolean): List<Pair<Int, RemoteViews>> =
        ids.map { id -> id to views.build(state, id, tabs.selected(id, state.board), updating) }

    private companion object {
        /** One per process: refresh jobs, the sweep, the update dot and tab taps all draw through it. */
        val GATE = SerialRender()
    }
}
