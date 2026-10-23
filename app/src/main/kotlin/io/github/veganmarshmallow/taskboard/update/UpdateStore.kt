package io.github.veganmarshmallow.taskboard.update

import android.content.Context
import io.github.veganmarshmallow.taskboard.core.update.UpdateStatus
import io.github.veganmarshmallow.taskboard.core.update.UpdateStatusCodec

/** Caches the last update check, so the app and the widget's dot can show it without a request. */
class UpdateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): UpdateStatus = UpdateStatusCodec.decode(prefs.getString(KEY, null))

    @android.annotation.SuppressLint("ApplySharedPref")
    fun save(status: UpdateStatus) {
        prefs.edit().putString(KEY, UpdateStatusCodec.encode(status)).commit()
    }

    private companion object {
        const val FILE = "update_status"
        const val KEY = "status"
    }
}
