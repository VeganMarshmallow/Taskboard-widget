package io.github.veganmarshmallow.taskboard.core.board

/** A row of the widget's scrolling list. */
sealed class BoardRow {
    /** Stable id for RemoteViewsFactory.getItemId. Unique within one tab's rows. */
    abstract val id: Long

    /** [section] is the tab the row is listed in (it decides the badge, see [RowBadge]). */
    data class Item(val item: BoardItem, val section: Section = Section.TODO) : BoardRow() {
        override val id: Long get() = item.number.toLong()
    }

    /** "+N more" under a capped tab; opens the matching list on GitHub. */
    data class More(val section: Section, val hidden: Int) : BoardRow() {
        override val id: Long get() = -100L - section.ordinal
    }

    /**
     * The tab has nothing in it; the app shows a message chosen by [section]. [filtered]: the tab
     * is empty because the board filter (#59) hides its tasks, so the message says that instead.
     */
    data class Empty(val section: Section, val filtered: Boolean = false) : BoardRow() {
        override val id: Long get() = -200L - section.ordinal
    }
}
