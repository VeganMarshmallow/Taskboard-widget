package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.board.BoardItem
import io.github.veganmarshmallow.taskboard.core.board.BoardLinks
import io.github.veganmarshmallow.taskboard.core.board.BoardRow
import io.github.veganmarshmallow.taskboard.core.board.RowBadge
import io.github.veganmarshmallow.taskboard.core.board.Section

/** Maps one [BoardRow] to its RemoteViews, with fill-in intents for taps. */
class RowViews(private val context: Context) {
    private val links = BoardLinks()

    fun build(row: BoardRow): RemoteViews = when (row) {
        is BoardRow.Item -> item(row.item)
        is BoardRow.More -> note(moreText(row), row)
        is BoardRow.Empty -> note(context.getString(EmptyText.of(row.section)), row)
    }

    private fun moreText(row: BoardRow.More): String {
        val plural = if (row.section == Section.DONE) R.plurals.more_done else R.plurals.more_items
        return context.resources.getQuantityString(plural, row.hidden, row.hidden)
    }

    private fun item(item: BoardItem) = RemoteViews(context.packageName, R.layout.row_item).apply {
        setTextViewText(R.id.item_text, item.displayText)
        val badge = RowBadge.of(item)
        setTextViewText(R.id.item_badge, badge)
        setViewVisibility(R.id.item_badge, if (badge.isEmpty()) View.GONE else View.VISIBLE)
        setOnClickFillInIntent(R.id.item_root, RowAction.Preview(item.number).toFillIn())
        if (item.canApprove) {
            setViewVisibility(R.id.item_approve, View.VISIBLE)
            setOnClickFillInIntent(R.id.item_approve, RowAction.Approve(item.number, item.title).toFillIn())
        } else {
            setViewVisibility(R.id.item_approve, View.GONE)
        }
    }

    private fun note(text: String, row: BoardRow) = RemoteViews(context.packageName, R.layout.row_note).apply {
        setTextViewText(R.id.note_text, text)
        // Rows are recycled, so a non-tappable note still needs a (harmless) fill-in to clear the old one.
        val action = links.forRow(row)?.let { RowAction.Open(it).toFillIn() } ?: RowAction.NONE
        setOnClickFillInIntent(R.id.note_text, action)
    }

    companion object {
        const val VIEW_TYPE_COUNT = 2
    }
}
