package io.github.veganmarshmallow.taskboard.data

import android.content.Context
import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.board.TabSelection

/** Remembers the tab the user picked, per widget id. Which tab to show is decided by [TabSelection]. */
class TabStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun selected(widgetId: Int, board: Board?): Section = TabSelection.resolve(prefs.getString(key(widgetId), null), board)

    fun select(widgetId: Int, section: Section) {
        prefs.edit().putString(key(widgetId), TabSelection.key(section)).apply()
    }

    /** Forgets removed widgets, so a recycled id starts on the default tab. */
    fun clear(widgetIds: IntArray) {
        val edit = prefs.edit()
        widgetIds.forEach { edit.remove(key(it)) }
        edit.apply()
    }

    private fun key(widgetId: Int) = "tab_$widgetId"

    private companion object {
        const val FILE = "widget_tabs"
    }
}
