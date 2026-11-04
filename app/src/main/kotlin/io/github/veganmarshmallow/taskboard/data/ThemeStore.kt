package io.github.veganmarshmallow.taskboard.data

import android.content.Context
import io.github.veganmarshmallow.taskboard.core.theme.ThemeChoice

/** The theme picked in settings (#46). Nothing saved yet, or an unknown value, reads as [ThemeChoice.DEFAULT]. */
class ThemeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): ThemeChoice = ThemeChoice.decode(prefs.getString(KEY, null))

    /** Saved at once (commit), so the widget redraw that follows reads the new choice. */
    fun save(choice: ThemeChoice) {
        prefs.edit().putString(KEY, ThemeChoice.encode(choice)).commit()
    }

    private companion object {
        const val FILE = "theme"
        const val KEY = "choice"
    }
}
