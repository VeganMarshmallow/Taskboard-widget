package io.github.veganmarshmallow.taskboard.core.board

import java.time.Instant

/** A section's visible items (capped) and its true total. */
data class SectionContent(val section: Section, val items: List<BoardItem>, val totalCount: Int) {
    val hiddenCount: Int get() = totalCount - items.size
}

/** The whole board as the widget shows it. [sections] always holds every [Section], in display order. */
data class Board(
    val sections: List<SectionContent>,
    val fetchedAt: Instant,
) {
    fun section(section: Section): SectionContent = sections.first { it.section == section }

    fun count(section: Section): Int = section(section).totalCount

    val doneLast7Days: Int get() = count(Section.DONE)

    /** True when no open task is shown anywhere (recently done tasks don't count). */
    val isEmpty: Boolean get() = sections.all { it.section == Section.DONE || it.totalCount == 0 }
}
