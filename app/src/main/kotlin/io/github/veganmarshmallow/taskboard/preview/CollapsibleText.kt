package io.github.veganmarshmallow.taskboard.preview

import android.content.Context
import android.text.StaticLayout
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.preview.NoteFold

/**
 * A text that starts collapsed to [collapsedLines] lines (one, #46), with a "Show more" / "Show less"
 * toggle that only appears when the text is longer than that (task #43). The rules are [NoteFold]'s. Children, from the layout: the
 * [TextView] first, the toggle second. Whether the text overflows is decided while measuring, so it
 * is right for the actual width and font size, and a screenshot taken after one layout pass is too.
 */
class CollapsibleText @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    var collapsedLines: Int = DEFAULT_COLLAPSED_LINES

    var expanded: Boolean = false
        private set

    private val text: TextView get() = getChildAt(0) as TextView
    private val toggle: TextView get() = getChildAt(1) as TextView

    init {
        orientation = VERTICAL
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        toggle.setOnClickListener { toggle() }
    }

    /** Shows [value], collapsed; binding the same text again keeps it expanded or collapsed. */
    fun setText(value: String) {
        if (value == text.text.toString()) return
        text.text = value
        expanded = false
        requestLayout()
    }

    fun toggle() {
        expanded = !expanded
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight - text.paddingLeft - text.paddingRight
        val measurable = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && width > 0
        val shape = NoteFold.shape(if (measurable) lineCount(width) else 0, expanded, collapsedLines)
        // Only touch the children when something changed: each setter requests another layout.
        if (text.maxLines != shape.maxLines) text.maxLines = shape.maxLines
        val visibility = if (shape.showToggle) VISIBLE else GONE
        if (toggle.visibility != visibility) toggle.visibility = visibility
        val label = context.getString(if (shape.toggleShowsLess) R.string.preview_show_less else R.string.preview_show_more)
        if (toggle.text.toString() != label) toggle.text = label
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** How many lines the whole text takes at [width] px, laid out the way the TextView lays it out. */
    private fun lineCount(width: Int): Int {
        val value = text.text
        return StaticLayout.Builder.obtain(value, 0, value.length, text.paint, width)
            .setLineSpacing(text.lineSpacingExtra, text.lineSpacingMultiplier)
            .setIncludePad(text.includeFontPadding)
            .setBreakStrategy(text.breakStrategy)
            .setHyphenationFrequency(text.hyphenationFrequency)
            .build()
            .lineCount
    }

    companion object {
        const val DEFAULT_COLLAPSED_LINES = NoteFold.COLLAPSED_LINES
    }
}
