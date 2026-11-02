package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.board.BoardItem
import io.github.veganmarshmallow.taskboard.core.board.BoardLinks
import io.github.veganmarshmallow.taskboard.core.board.BoardRow
import io.github.veganmarshmallow.taskboard.core.board.RowBadge
import io.github.veganmarshmallow.taskboard.core.board.RowFit
import io.github.veganmarshmallow.taskboard.core.board.Section

/**
 * Maps one [BoardRow] to its RemoteViews, with fill-in intents for taps. [build]'s widthDp is the widget's width
 * (null when unknown): narrow widths and big fonts get the two-line task row (#49, [RowFit]).
 */
class RowViews(private val context: Context) {
    private val links = BoardLinks()
    private val mergeLanes = MergeLaneViews(context)

    fun build(row: BoardRow, widthDp: Int? = null): RemoteViews = when (row) {
        is BoardRow.Item -> item(row.item, row.section, layoutFor(widthDp))
        is BoardRow.More -> note(moreText(row), row)
        is BoardRow.Empty -> note(context.getString(EmptyText.of(row.section)), row)
    }

    private fun moreText(row: BoardRow.More): String {
        val plural = if (row.section == Section.DONE) R.plurals.more_done else R.plurals.more_items
        return context.resources.getQuantityString(plural, row.hidden, row.hidden)
    }

    /** The task row layout for a widget [widthDp] wide at the current font scale. */
    fun layoutFor(widthDp: Int?): Int =
        if (RowFit.isNarrow(widthDp, context.resources.configuration.fontScale)) R.layout.row_item_narrow else R.layout.row_item

    private fun item(item: BoardItem, section: Section, layout: Int) = RemoteViews(context.packageName, layout).apply {
        setTextViewText(R.id.item_text, item.displayText)
        val badge = RowBadge.of(item, section)
        setTextViewText(R.id.item_badge, badge)
        setViewVisibility(R.id.item_badge, if (badge.isEmpty()) View.GONE else View.VISIBLE)
        setOnClickFillInIntent(R.id.item_root, RowAction.Preview(item.number).toFillIn())
        if (item.canApprove) {
            setViewVisibility(R.id.item_approve, View.VISIBLE)
            setOnClickFillInIntent(R.id.item_approve, RowAction.Approve(item.number, item.title).toFillIn())
        } else {
            setViewVisibility(R.id.item_approve, View.GONE)
        }
        mergeLanes.bind(this, item)
        if (layout == R.layout.row_item_narrow) {
            // The button line takes no room in a row without buttons.
            setViewVisibility(R.id.item_actions, if (item.canApprove || item.canMerge) View.VISIBLE else View.GONE)
        }
    }

    private fun note(text: String, row: BoardRow) = RemoteViews(context.packageName, R.layout.row_note).apply {
        setTextViewText(R.id.note_text, text)
        // Rows are recycled, so a non-tappable note still needs a (harmless) fill-in to clear the old one.
        val action = links.forRow(row)?.let { RowAction.Open(it).toFillIn() } ?: RowAction.NONE
        setOnClickFillInIntent(R.id.note_text, action)
    }

    companion object {
        /** row_item, row_item_narrow and row_note. */
        const val VIEW_TYPE_COUNT = 3
    }
}
