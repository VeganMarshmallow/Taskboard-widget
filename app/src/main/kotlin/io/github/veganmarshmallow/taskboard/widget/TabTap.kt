package io.github.veganmarshmallow.taskboard.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import io.github.veganmarshmallow.taskboard.core.board.TabSelection
import io.github.veganmarshmallow.taskboard.core.work.RenderUntilStable
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.data.TabStore

/**
 * A tap on a status tab: remember the pick for that widget and redraw it from the cached board.
 *
 * This is quick local work (no network), so it runs right away in the receiver instead of queueing
 * on [BackgroundWork]'s thread, where it would wait behind a slow refresh. A refresh may save a
 * newer board meanwhile; [RenderUntilStable] makes sure the older one is never left on screen.
 */
object TabTap {
    /** False (and nothing changes) for an intent without a valid widget id and tab. */
    fun handle(context: Context, intent: Intent): Boolean {
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val tab = TabSelection.parse(intent.getStringExtra(WidgetIntents.EXTRA_TAB))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || tab == null) return false
        TabStore(context).select(widgetId, tab)
        val store = StateStore(context)
        val renderer = WidgetRenderer(context)
        RenderUntilStable.run(store::load, { renderer.render(it, intArrayOf(widgetId)) })
        return true
    }
}
