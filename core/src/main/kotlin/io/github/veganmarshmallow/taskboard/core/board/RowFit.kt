package io.github.veganmarshmallow.taskboard.core.board

/**
 * #49: whether a task row fits on one line, or needs its narrow two-line layout (title on top, Approve or Merge
 * and its reason on a line of their own below).
 *
 * A one-line row holds the badge, the title, the merge reason (up to 120dp) and the Merge button. Text grows with
 * the font scale, so what counts is the width per unit of font scale: [WIDE_MIN_DP] dp at font scale 1, twice that
 * at font scale 2. Below it the title would get only a few dp, or none (it showed "PR …" at 180dp and font 2.0).
 */
object RowFit {
    /** The narrowest widget width, in dp at font scale 1, where a one-line row still leaves its title a word or two. */
    const val WIDE_MIN_DP = 300

    /**
     * True when rows of a widget [widthDp] wide should use the narrow layout at [fontScale]. An unknown width (null,
     * zero or negative: a launcher that never sent the widget's size) keeps the one-line row of before #49. A font
     * scale that is not a positive number counts as 1.
     */
    fun isNarrow(widthDp: Int?, fontScale: Float): Boolean {
        if (widthDp == null || widthDp <= 0) return false
        val scale = if (fontScale.isFinite() && fontScale > 0f) fontScale else 1f
        return widthDp < WIDE_MIN_DP * scale
    }
}
