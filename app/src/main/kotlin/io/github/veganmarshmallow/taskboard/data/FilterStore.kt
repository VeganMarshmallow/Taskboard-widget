package io.github.veganmarshmallow.taskboard.data

import android.content.Context
import io.github.veganmarshmallow.taskboard.core.filter.BoardFilter
import io.github.veganmarshmallow.taskboard.core.filter.BoardFilterCodec

/**
 * The board filter (#59), one for the app and every widget: the owner sets it once and every view
 * of the board follows. Read on each render, so a change shows on the next redraw.
 */
class FilterStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): BoardFilter = BoardFilterCodec.decode(prefs.getString(KEY, null))

    /** Synchronous, so the redraw that follows reads the new filter. */
    @android.annotation.SuppressLint("ApplySharedPref")
    fun save(filter: BoardFilter) {
        prefs.edit().putString(KEY, BoardFilterCodec.encode(filter)).commit()
    }

    fun clear() = save(BoardFilter.NONE)

    private companion object {
        const val FILE = "board_filter"
        const val KEY = "filter"
    }
}
