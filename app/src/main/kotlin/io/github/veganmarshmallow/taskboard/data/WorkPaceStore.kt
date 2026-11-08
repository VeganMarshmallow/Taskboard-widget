package io.github.veganmarshmallow.taskboard.data

import android.annotation.SuppressLint
import android.content.Context
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.pace.BoardSettingsApi
import io.github.veganmarshmallow.taskboard.core.pace.WorkPace

/**
 * The last Work pace (#83) this phone read from or wrote to the board's `.board/settings.json`,
 * the file being the source of truth. Merge all and the Merge button use it without a request;
 * auto merge reads the file first ([fresh]). Saved at once (commit), like [AutoMergePrefs].
 */
@SuppressLint("ApplySharedPref")
class WorkPaceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** A missing, corrupt or unknown value reads as Save usage. */
    fun cached(): WorkPace = WorkPace.of(runCatching { prefs.getString(PACE, null) }.getOrNull())

    fun remember(pace: WorkPace) {
        if (runCatching { prefs.getString(PACE, null) }.getOrNull() != pace.value) prefs.edit().putString(PACE, pace.value).commit()
    }

    /** The stored pace read now with [api] (and remembered); the cached one if it can't be read. */
    fun fresh(api: GitHubApi): WorkPace = when (val read = BoardSettingsApi(api).pace()) {
        is Outcome.Success -> read.value.also(::remember)
        is Outcome.Failure -> cached()
    }

    private companion object {
        const val FILE = "work_pace"
        const val PACE = "pace"
    }
}
