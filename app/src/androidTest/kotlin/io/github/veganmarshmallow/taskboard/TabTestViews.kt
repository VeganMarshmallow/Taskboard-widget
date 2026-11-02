package io.github.veganmarshmallow.taskboard

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.widget.TextView
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.widget.TabViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.Locale

/**
 * Test helpers for the tab row and header: find the label a tab shows, check every label fits its tab, check the
 * header buttons fit the widget, measure where a view sits, and contexts with another font scale, theme or locale.
 */
object TabTestViews {
    /** The label the tab currently shows. Exactly one of its two labels is visible. */
    fun View.shownTab(section: Section): TextView {
        val ids = TabViews.ids(section)
        val shown = listOf(ids.label, ids.selectedLabel).map { findViewById<TextView>(it) }.filter { it.visibility == View.VISIBLE }
        assertEquals("$section: visible labels", 1, shown.size)
        return shown.single()
    }

    fun View.isHighlighted(section: Section): Boolean =
        findViewById<View>(TabViews.ids(section).selectedLabel).visibility == View.VISIBLE

    fun View.tabSlot(section: Section): View = findViewById(TabViews.ids(section).slot)

    /** Every tab label fits its tab: at most two lines, broken between words, nothing cut off, not below 8dp. */
    fun tabFitProblems(widget: View, what: String): List<String> {
        val density = widget.resources.displayMetrics.density
        return Section.entries.flatMap { section ->
            val tab = widget.shownTab(section)
            val layout = tab.layout
            val name = "$what $section '${tab.text}' @${"%.1f".format(tab.textSize / density)}dp"
            val problems = mutableListOf<String>()
            if (layout.lineCount > 2) problems += "$name: ${layout.lineCount} lines"
            if ((0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 }) problems += "$name: cut off"
            if ((0 until layout.lineCount - 1).any { tab.text[layout.getLineEnd(it) - 1] != ' ' }) problems += "$name: breaks inside a word"
            if (layout.height > tab.height - tab.paddingTop - tab.paddingBottom) problems += "$name: taller than the tab"
            if (tab.textSize < 8 * density - 0.5f) problems += "$name: below 8dp"
            problems
        }
    }

    fun assertTabsFit(widget: View, what: String) {
        val problems = tabFitProblems(widget, what)
        assertTrue(problems.joinToString("; "), problems.isEmpty())
    }

    /**
     * #28: every visible header button ([R.id.refresh], [R.id.new_task], the update dot) keeps its full size and lies
     * inside the widget's content area (inside its padding), on either side in RTL, and top to bottom.
     */
    fun headerFitProblems(widget: View, what: String): List<String> {
        val left = widget.paddingLeft
        val right = widget.width - widget.paddingRight
        val bottom = widget.height - widget.paddingBottom
        return listOf("refresh" to R.id.refresh, "new_task" to R.id.new_task, "update_dot" to R.id.update_dot).mapNotNull { (name, id) ->
            val button = widget.findViewById<View>(id)
            if (button.visibility != View.VISIBLE) return@mapNotNull null
            val x = button.leftIn(widget)
            val wanted = button.layoutParams.width
            when {
                button.width != wanted -> "$what $name: ${button.width}px wide, wants ${wanted}px"
                x < left || x + button.width > right -> "$what $name: spans ${x}..${x + button.width}px, widget content is $left..${right}px"
                button.height != button.layoutParams.height || button.topIn(widget) + button.height > bottom ->
                    "$what $name: ${button.height}px tall at y=${button.topIn(widget)}, widget content ends at ${bottom}px"
                else -> null
            }
        }
    }

    fun assertHeaderFits(widget: View, what: String) {
        val problems = headerFitProblems(widget, what)
        assertTrue(problems.joinToString("; "), problems.isEmpty())
    }

    /** This view's left edge in [root]'s coordinates (sum of the lefts up the parent chain). */
    fun View.leftIn(root: View): Int {
        var x = 0
        var v: View = this
        while (v !== root) { x += v.left; v = v.parent as View }
        return x
    }

    /** This view's top edge in [root]'s coordinates (sum of the tops up the parent chain). */
    fun View.topIn(root: View): Int {
        var y = 0
        var v: View = this
        while (v !== root) { y += v.top; v = v.parent as View }
        return y
    }

    fun configured(base: Context, fontScale: Float = 1f, night: Boolean? = null, locale: Locale? = null): Context {
        val config = Configuration(base.resources.configuration)
        config.fontScale = fontScale
        if (night != null) {
            val mode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
        }
        if (locale != null) {
            config.setLocale(locale)
            config.setLayoutDirection(locale)
        }
        return base.createConfigurationContext(config)
    }
}
