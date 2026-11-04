package io.github.veganmarshmallow.taskboard.core.preview

/**
 * How Claude's note in the task sheet folds (#43, #46): collapsed to [COLLAPSED_LINES] line with
 * "Show more" when it is longer, the whole text with "Show less" once expanded, and no toggle at
 * all for a note that already fits.
 */
object NoteFold {
    /** The owner asked for one line plus "Show more" (#46). */
    const val COLLAPSED_LINES = 1

    /** [maxLines] for the text ([Int.MAX_VALUE]: no limit), whether the toggle shows, and what it says. */
    data class Shape(val maxLines: Int, val showToggle: Boolean, val toggleShowsLess: Boolean)

    /**
     * [lineCount] is how many lines the whole note takes at the current width (0 or less when it
     * could not be measured yet: shown whole, no toggle). [collapsedLines] below 1 counts as 1.
     */
    fun shape(lineCount: Int, expanded: Boolean, collapsedLines: Int = COLLAPSED_LINES): Shape {
        val limit = collapsedLines.coerceAtLeast(1)
        val overflows = lineCount > limit
        return when {
            !overflows -> Shape(Int.MAX_VALUE, showToggle = false, toggleShowsLess = false)
            expanded -> Shape(Int.MAX_VALUE, showToggle = true, toggleShowsLess = true)
            else -> Shape(limit, showToggle = true, toggleShowsLess = false)
        }
    }
}
