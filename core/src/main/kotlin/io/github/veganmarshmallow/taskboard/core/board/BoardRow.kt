package io.github.veganmarshmallow.taskboard.core.board

/** A row of the widget's scrolling list. */
sealed class BoardRow {
    /** Stable id for RemoteViewsFactory.getItemId. Unique within one tab's rows. */
    abstract val id: Long

    data class Item(val item: BoardItem) : BoardRow() {
        override val id: Long get() = item.number.toLong()
    }

    /** "+N more" under a capped tab; opens the matching list on GitHub. */
    data class More(val section: Section, val hidden: Int) : BoardRow() {
        override val id: Long get() = -100L - section.ordinal
    }

    /** The tab has nothing in it; the app shows a message chosen by [section]. */
    data class Empty(val section: Section) : BoardRow() {
        override val id: Long get() = -200L - section.ordinal
    }
}
