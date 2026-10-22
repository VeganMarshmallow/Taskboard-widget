package io.github.veganmarshmallow.taskboard.core.board

/** A row of the widget's scrolling list. */
sealed class BoardRow {
    /** Stable id for RemoteViewsFactory.getItemId. */
    abstract val id: Long

    data class Header(val section: Section, val count: Int) : BoardRow() {
        override val id: Long get() = -1L - section.ordinal
    }

    data class Item(val item: BoardItem) : BoardRow() {
        override val id: Long get() = item.number.toLong()
    }

    /** "+N more" under a capped section; opens the issue list on GitHub. */
    data class More(val section: Section, val hidden: Int) : BoardRow() {
        override val id: Long get() = -100L - section.ordinal
    }

    data object Empty : BoardRow() {
        override val id: Long get() = -200L
    }

    data class Done(val count: Int) : BoardRow() {
        override val id: Long get() = -300L
    }
}
