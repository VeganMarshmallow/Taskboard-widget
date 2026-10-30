package io.github.veganmarshmallow.taskboard.data

import android.content.Context
import io.github.veganmarshmallow.taskboard.core.work.RetryStreak
import io.github.veganmarshmallow.taskboard.core.work.RetryStreakCodec
import io.github.veganmarshmallow.taskboard.core.work.RetryStreakStore

/** Persists the streak of failed refreshes, so the retry backoff keeps growing across processes. */
class RetryStreakPrefs(context: Context) : RetryStreakStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): RetryStreak? = RetryStreakCodec.decode(prefs.getString(KEY, null))

    override fun save(streak: RetryStreak?) {
        val edit = prefs.edit()
        if (streak == null) edit.remove(KEY) else edit.putString(KEY, RetryStreakCodec.encode(streak))
        edit.apply()
    }

    private companion object {
        const val FILE = "retry_streak"
        const val KEY = "streak"
    }
}
