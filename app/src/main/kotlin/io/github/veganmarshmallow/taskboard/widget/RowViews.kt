package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.board.BoardItem
import io.github.veganmarshmallow.taskboard.core.board.BoardLinks
import io.github.veganmarshmallow.taskboard.core.board.BoardRow
import io.github.veganmarshmallow.taskboard.core.board.NeedsYouReason
import io.github.veganmarshmallow.taskboard.core.model.Priority

/** Maps one [BoardRow] to its RemoteViews, with fill-in intents for taps. */
class RowViews(private val context: Context) {
    private val links = BoardLinks()

    fun build(row: BoardRow): RemoteViews = when (row) {
        is BoardRow.Header -> header(row)
        is BoardRow.Item -> item(row.item)
        is BoardRow.More -> note(context.resources.getQuantityString(R.plurals.more_items, row.hidden, row.hidden), row)
        BoardRow.Empty -> note(context.getString(R.string.empty_board), row)
        is BoardRow.Done -> note(context.getString(R.string.done_count, row.count), row)
    }

    private fun header(row: BoardRow.Header) = RemoteViews(context.packageName, R.layout.row_header).apply {
        setTextViewText(R.id.section_title, row.section.title)
        setTextViewText(R.id.section_count, row.count.toString())
    }

    private fun item(item: BoardItem) = RemoteViews(context.packageName, R.layout.row_item).apply {
        setTextViewText(R.id.item_text, item.displayText)
        val badge = badge(item)
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
        links.forRow(row)?.let { setOnClickFillInIntent(R.id.note_text, RowAction.Open(it).toFillIn()) }
    }

    /** Short marker before the title: high priority, and why it needs the owner. */
    private fun badge(item: BoardItem): String = buildString {
        if (item.priority == Priority.HIGH) append("!")
        when (item.needsYouReason) {
            NeedsYouReason.QUESTION -> append("?")
            NeedsYouReason.REVIEW -> append("PR")
            null -> Unit
        }
    }

    companion object {
        const val VIEW_TYPE_COUNT = 3
    }
}
