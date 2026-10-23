package io.github.veganmarshmallow.taskboard.data

import android.content.Context
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetStateCodec
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetStateStore

/** Persists the last [WidgetState] so the list factory and later refreshes can read it. */
class StateStore(context: Context) : WidgetStateStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): WidgetState = WidgetStateCodec.decode(prefs.getString(KEY, null))

    /** Synchronous so the list factory reads the new state right after. Called off the main thread. */
    @android.annotation.SuppressLint("ApplySharedPref")
    override fun save(state: WidgetState) {
        prefs.edit().putString(KEY, WidgetStateCodec.encode(state)).commit()
    }

    private companion object {
        const val FILE = "widget_state"
        const val KEY = "state"
    }
}
