package io.github.veganmarshmallow.taskboard

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.widget.TextView
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.widget.TabViews
import org.junit.Assert.assertEquals
import java.util.Locale

/** Test helpers for the tab row: find the label a tab shows, and contexts with another font scale, theme or locale. */
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
