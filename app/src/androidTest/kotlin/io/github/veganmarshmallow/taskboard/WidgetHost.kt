package io.github.veganmarshmallow.taskboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import io.github.veganmarshmallow.taskboard.core.board.BoardRow
import io.github.veganmarshmallow.taskboard.core.board.BoardRows
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.widget.RowViews
import io.github.veganmarshmallow.taskboard.widget.WidgetViews

/**
 * Renders the widget's real RemoteViews into a plain view tree, the way a launcher would: the outer
 * views from [WidgetViews], and each list row from [RowViews] (a launcher gets those through the
 * RemoteViewsService; here they are applied directly into a column). Must run on the main thread.
 */
class WidgetHost(private val context: Context) {
    class Rendered(val root: View, val widget: View)

    fun render(state: WidgetState, widthDp: Int, heightDp: Int): Rendered {
        val density = context.resources.displayMetrics.density
        val frame = FrameLayout(context).apply { setBackgroundColor(WALLPAPER) }
        val pad = (16 * density).toInt()
        frame.setPadding(pad, pad, pad, pad)
        val widget = WidgetViews(context).build(state, WIDGET_ID).apply(context, frame)
        frame.addView(widget, FrameLayout.LayoutParams((widthDp * density).toInt(), (heightDp * density).toInt()))

        replaceListWithRows(widget, state.board?.let(BoardRows::of).orEmpty())

        val w = ((widthDp + 32) * density).toInt()
        val h = ((heightDp + 32) * density).toInt()
        frame.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        frame.layout(0, 0, w, h)
        return Rendered(frame, widget)
    }

    /**
     * Swaps the ListView for a plain column holding the same row views. A launcher fills the list
     * through the RemoteViewsService; doing it directly keeps the capture synchronous and avoids
     * the list's remote adapter (bound to a widget id no launcher owns here) replacing the rows.
     */
    private fun replaceListWithRows(widget: View, rows: List<BoardRow>) {
        val list = widget.findViewById<View>(R.id.board_list)
        val parent = list.parent as ViewGroup
        val column = LinearLayout(context).apply {
            id = R.id.board_list
            orientation = LinearLayout.VERTICAL
            visibility = list.visibility
        }
        val rowViews = RowViews(context)
        rows.forEach { column.addView(rowViews.build(it).apply(context, column)) }
        val index = parent.indexOfChild(list)
        parent.removeViewAt(index)
        parent.addView(column, index, list.layoutParams)
    }

    companion object {
        const val WIDGET_ID = 1
        private val WALLPAPER = Color.rgb(0x5B, 0x6B, 0x7F)

        fun toBitmap(view: View): Bitmap =
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }
}
