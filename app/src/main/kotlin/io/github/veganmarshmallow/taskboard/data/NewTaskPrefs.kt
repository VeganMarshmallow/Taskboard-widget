package io.github.veganmarshmallow.taskboard.data

import android.annotation.SuppressLint
import android.content.Context
import io.github.veganmarshmallow.taskboard.core.task.NewTaskStore

/**
 * The New task form's draft and submit marker in app-private prefs. Writes are synchronous
 * (`commit`), so a process killed right after still finds them on disk.
 */
class NewTaskPrefs(context: Context) : NewTaskStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var draft: String?
        get() = prefs.getString(KEY_DRAFT, null)
        set(value) = put(KEY_DRAFT, value)

    override var marker: String?
        get() = prefs.getString(KEY_MARKER, null)
        set(value) = put(KEY_MARKER, value)

    @SuppressLint("ApplySharedPref")
    private fun put(key: String, value: String?) {
        val edit = prefs.edit()
        if (value == null) edit.remove(key) else edit.putString(key, value)
        edit.commit()
    }

    companion object {
        const val FILE = "new_task"
        private const val KEY_DRAFT = "draft"
        private const val KEY_MARKER = "submit_marker"
    }
}
