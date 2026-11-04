package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import io.github.veganmarshmallow.taskboard.core.board.BoardRow
import io.github.veganmarshmallow.taskboard.core.board.BoardRows
import io.github.veganmarshmallow.taskboard.core.filter.filtered
import io.github.veganmarshmallow.taskboard.data.FilterStore
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.data.TabStore

/**
 * Reads the cached board and this widget's selected tab in onDataSetChanged (which runs on a binder thread, so disk access is fine)
 * and turns rows into views via [RowViews]. It never touches the network; [WidgetRefresher] does.
 * Used on Android 11 and older only: newer ones get the rows inside the widget update ([ListBinding]).
 */
class BoardListFactory(private val context: Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
    private var rows: List<BoardRow> = emptyList()
    private var widthDp: Int? = null
    private val rowViews = RowViews(context)

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        val filter = FilterStore(context).load()
        val board = StateStore(context).load().board?.filtered(filter)
        rows = board?.let { BoardRows.of(it, TabStore(context).selected(widgetId, it), filter.isActive) }.orEmpty()
        widthDp = WidgetSize.widthDp(context, widgetId)
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews? = rows.getOrNull(position)?.let { rowViews.build(it, widthDp) }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = RowViews.VIEW_TYPE_COUNT

    override fun getItemId(position: Int): Long = rows.getOrNull(position)?.id ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
