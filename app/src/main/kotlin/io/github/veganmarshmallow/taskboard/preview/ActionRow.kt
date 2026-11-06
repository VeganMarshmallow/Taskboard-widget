package io.github.veganmarshmallow.taskboard.preview

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/**
 * The task sheet's actions (#46 round 2): one line ("Open on GitHub · Send · Merge") whenever the
 * shown buttons fit at their natural width. When they don't (a narrow phone with the largest font,
 * or Retry shown too), a plain row would squeeze "Open on GitHub" to nothing and clip the last
 * button, so the row stacks instead: one full-width button per line, nothing hidden or cut.
 * The layout's own (one-line) layout params are the row shape; stacking only overrides them.
 */
class ActionRow @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private lateinit var rowShape: List<LayoutParams>
    private val gap = (STACK_GAP_DP * resources.displayMetrics.density).toInt()

    /** True while the actions are stacked one per line. */
    val isStacked: Boolean get() = orientation == VERTICAL

    override fun onFinishInflate() {
        super.onFinishInflate()
        rowShape = (0 until childCount).map { LayoutParams(getChildAt(it).layoutParams as LayoutParams) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val stack = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && naturalWidth() > available
        if (stack != isStacked) arrange(stack)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** The width the shown buttons need on one line, each at its natural (un-ellipsized) width. */
    private fun naturalWidth(): Int {
        var total = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            total += child.measuredWidth + rowShape[i].marginStart + rowShape[i].marginEnd
        }
        return total
    }

    /** Only called when the shape changes, so the layout request this makes settles after one more pass. */
    private fun arrange(stacked: Boolean) {
        for (i in 0 until childCount) {
            getChildAt(i).layoutParams = if (stacked) {
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { if (i > 0) topMargin = gap }
            } else {
                LayoutParams(rowShape[i])
            }
        }
        orientation = if (stacked) VERTICAL else HORIZONTAL
    }

    private companion object {
        const val STACK_GAP_DP = 8
    }
}
