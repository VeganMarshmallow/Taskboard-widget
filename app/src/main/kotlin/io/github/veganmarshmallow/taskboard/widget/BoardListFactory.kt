package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import io.github.veganmarshmallow.taskboard.core.board.BoardRow
import io.github.veganmarshmallow.taskboard.core.board.BoardRows
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.data.TabStore

/**
 * Reads the cached board and this widget's selected tab in onDataSetChanged (which runs on a binder thread, so disk access is fine)
 * and turns rows into views via [RowViews]. It never touches the network; [WidgetRefresher] does.
 */
class BoardListFactory(private val context: Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
    private var rows: List<BoardRow> = emptyList()
    private val rowViews = RowViews(context)

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        val board = StateStore(context).load().board
        rows = board?.let { BoardRows.of(it, TabStore(context).selected(widgetId, it)) }.orEmpty()
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews? = rows.getOrNull(position)?.let(rowViews::build)

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = RowViews.VIEW_TYPE_COUNT

    override fun getItemId(position: Int): Long = rows.getOrNull(position)?.id ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
