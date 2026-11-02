package io.github.veganmarshmallow.taskboard.core.board

/**
 * #49: whether a task row fits on one line, or needs its narrow two-line layout (title on top, Approve or Merge
 * and its reason on a line of their own below).
 *
 * What a one-line row needs depends on what it holds besides the title, so each row is judged by its own content
 * ([Extras]). Text grows with the font scale, so what counts is the width per unit of font scale: the threshold
 * dp at font scale 1, twice that at font scale 2. Below it the title would get only a few dp, or none (a PR row
 * showed "PR …" at 180dp and font 2.0). Rows without a button never need the second line.
 */
object RowFit {
    /** What a row shows next to its badge and title. */
    enum class Extras {
        /** Nothing: the title has the row to itself. */
        NONE,

        /** Approve, or a Merge button with no reason beside it (~80dp). */
        BUTTON,

        /** A muted Merge with its reason (up to 120dp) beside it, or both buttons (~200dp). */
        BUTTON_AND_REASON,
    }

    /** The narrowest widget width, in dp at font scale 1, where a row with one button still leaves its title ~90dp. */
    const val BUTTON_MIN_DP = 220

    /** The same for a row with a merge reason beside its button. */
    const val REASON_MIN_DP = 300

    fun extrasOf(item: BoardItem): Extras {
        val reason = item.canMerge && item.mergeLane?.let { !it.isNext && it.wait != null } == true
        return when {
            reason || (item.canApprove && item.canMerge) -> Extras.BUTTON_AND_REASON
            item.canApprove || item.canMerge -> Extras.BUTTON
            else -> Extras.NONE
        }
    }

    /**
     * True when a row with [extras] in a widget [widthDp] wide should use the narrow layout at [fontScale]. An
     * unknown width (null, zero or negative: a launcher that never sent the widget's size) keeps the one-line row
     * of before #49. A font scale that is not a positive number counts as 1.
     */
    fun isNarrow(widthDp: Int?, fontScale: Float, extras: Extras): Boolean {
        val minDp = when (extras) {
            Extras.NONE -> return false
            Extras.BUTTON -> BUTTON_MIN_DP
            Extras.BUTTON_AND_REASON -> REASON_MIN_DP
        }
        if (widthDp == null || widthDp <= 0) return false
        val scale = if (fontScale.isFinite() && fontScale > 0f) fontScale else 1f
        return widthDp < minDp * scale
    }
}
