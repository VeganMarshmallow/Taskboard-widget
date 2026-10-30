package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import android.os.Build
import android.widget.RemoteViews
import android.annotation.TargetApi
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.board.BoardRows
import io.github.veganmarshmallow.taskboard.core.board.Section

/**
 * Puts the task list into the widget's RemoteViews (task #40).
 *
 * Android 12+ ([embedsRows]): the rows of [selected] go inside the same update as the tabs, so
 * the launcher gets the highlight and the list in one piece, and the app never has to tell it that
 * the list changed. The RemoteViewsService way broke this from Android 15/16: AppWidgetManager
 * then fetches the service's rows itself, sends a tap's update later on a thread of its own, and
 * on a list change re-sends the views the system had *before* that update arrived. Those still
 * had the old tab highlighted, so the highlight came back about a second after the tap.
 *
 * Older Android: the list binds to [BoardListService] as before, and [WidgetRenderer] notifies it.
 */
class ListBinding(private val context: Context) {
    private val rows = RowViews(context)

    fun bind(views: RemoteViews, board: Board?, selected: Section, widgetId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setRemoteAdapter(R.id.board_list, items(board, selected))
        } else {
            views.setRemoteAdapter(R.id.board_list, WidgetIntents.listService(context, widgetId))
        }
    }

    @TargetApi(Build.VERSION_CODES.S)
    private fun items(board: Board?, selected: Section): RemoteViews.RemoteCollectionItems {
        val builder = RemoteViews.RemoteCollectionItems.Builder()
            .setHasStableIds(true)
            .setViewTypeCount(RowViews.VIEW_TYPE_COUNT)
        board?.let { BoardRows.of(it, selected) }.orEmpty().forEach { builder.addItem(it.id, rows.build(it)) }
        return builder.build()
    }

    companion object {
        /** True when the rows travel inside the widget's update, and the list never needs a notify. */
        fun embedsRows(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
}
