package io.github.veganmarshmallow.taskboard.data

import android.content.Context

/**
 * Holds the GitHub token in app-private SharedPreferences (MODE_PRIVATE; excluded from backup and
 * device transfer). The token is never logged or shown again after saving.
 */
class TokenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(): String? = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun hasToken(): Boolean = get() != null

    fun save(token: String) {
        prefs.edit().putString(KEY, token).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val FILE = "credentials"
        const val KEY = "github_token"
    }
}
