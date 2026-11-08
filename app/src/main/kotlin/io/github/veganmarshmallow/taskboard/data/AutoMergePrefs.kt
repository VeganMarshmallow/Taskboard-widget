package io.github.veganmarshmallow.taskboard.data

import android.annotation.SuppressLint
import android.content.Context

/**
 * The auto merge setting (#79), off until the owner turns it on, and the last failure it notified
 * (so the same failure is not notified on every poll). Saved at once (commit): the job that may run
 * right after a change reads the new value.
 */
@SuppressLint("ApplySharedPref")
class AutoMergePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** A value of another type under the key (a corrupt or hand-edited file) reads as off. */
    fun enabled(): Boolean = runCatching { prefs.getBoolean(ENABLED, false) }.getOrDefault(false)

    /** Turning it on or off forgets the last failure, so the next one is notified again. */
    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(ENABLED, on).remove(LAST_FAILURE).commit()
    }

    fun lastFailure(): String? = runCatching { prefs.getString(LAST_FAILURE, null) }.getOrNull()

    fun setLastFailure(key: String?) {
        if (key == lastFailure()) return
        val edit = prefs.edit()
        if (key == null) edit.remove(LAST_FAILURE) else edit.putString(LAST_FAILURE, key)
        edit.commit()
    }

    private companion object {
        const val FILE = "auto_merge"
        const val ENABLED = "enabled"
        const val LAST_FAILURE = "last_failure"
    }
}
