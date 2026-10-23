package io.github.veganmarshmallow.taskboard.preview

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * Lays children out left to right (right to left in RTL) and wraps onto new lines when a row is
 * full. Children are measured at their wrap size, capped at the available width. Spacing comes from
 * [horizontalSpacing] and [verticalSpacing] (px); child margins are ignored. Gone children are skipped.
 */
class FlowLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    var horizontalSpacing: Int = dp(6)
    var verticalSpacing: Int = dp(6)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val unbounded = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
        val childWidthSpec = if (unbounded) MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        else MeasureSpec.makeMeasureSpec(maxOf(0, maxWidth), MeasureSpec.AT_MOST)
        var x = 0
        var y = 0
        var lineHeight = 0
        var widest = 0
        for (child in visibleChildren()) {
            child.measure(childWidthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && !unbounded && x + child.measuredWidth > maxWidth) {
                y += lineHeight + verticalSpacing
                x = 0
                lineHeight = 0
            }
            x += child.measuredWidth
            widest = maxOf(widest, x)
            x += horizontalSpacing
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
        val contentHeight = if (widest == 0) 0 else y + lineHeight
        setMeasuredDimension(
            resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val maxWidth = width - paddingLeft - paddingRight
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var x = 0
        var y = paddingTop
        var lineHeight = 0
        for (child in visibleChildren()) {
            val w = child.measuredWidth
            val h = child.measuredHeight
            if (x > 0 && x + w > maxWidth) {
                y += lineHeight + verticalSpacing
                x = 0
                lineHeight = 0
            }
            val left = if (rtl) width - paddingRight - x - w else paddingLeft + x
            child.layout(left, y, left + w, y + h)
            x += w + horizontalSpacing
            lineHeight = maxOf(lineHeight, h)
        }
    }

    private fun visibleChildren(): List<View> = (0 until childCount).map(::getChildAt).filter { it.visibility != GONE }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
