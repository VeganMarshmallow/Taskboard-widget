package io.github.veganmarshmallow.taskboard.data

import android.content.Context
import io.github.veganmarshmallow.taskboard.core.work.RefreshMark
import io.github.veganmarshmallow.taskboard.core.work.RefreshMarkCodec
import io.github.veganmarshmallow.taskboard.core.work.RefreshMarkStore

/**
 * Persists the running refresh's id and start time (not an "updating" flag): after process death
 * the next process sees how old the mark is and treats it as dead once it is stale.
 */
class RefreshMarkPrefs(context: Context) : RefreshMarkStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): RefreshMark? = RefreshMarkCodec.decode(prefs.getString(KEY, null))

    /** Synchronous, so a process killed right after still leaves the mark on disk. */
    @android.annotation.SuppressLint("ApplySharedPref")
    override fun save(mark: RefreshMark?) {
        val edit = prefs.edit()
        if (mark == null) edit.remove(KEY) else edit.putString(KEY, RefreshMarkCodec.encode(mark))
        edit.commit()
    }

    private companion object {
        const val FILE = "refresh_mark"
        const val KEY = "mark"
    }
}
