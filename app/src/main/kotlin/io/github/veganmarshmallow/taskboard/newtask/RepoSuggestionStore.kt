package io.github.veganmarshmallow.taskboard.newtask

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.task.RepoSuggestions
import java.util.concurrent.Executors

/**
 * Repo suggestions for the New task form. The last list is cached in app-private preferences so
 * the form has suggestions at once, and offline; [refresh] reloads it from the board's newest
 * issues in the background. Failures are silent: suggestions are a convenience.
 */
class RepoSuggestionStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun cached(): List<String> =
        prefs.getString(KEY, null).orEmpty().split('\n').filter { it.isNotBlank() }.take(RepoSuggestions.MAX)

    /** Fetches fresh suggestions and calls [onLoaded] on the main thread when they changed. */
    fun refresh(onLoaded: (List<String>) -> Unit) {
        val api = NewTaskController.apiProvider(app) ?: return
        executor.execute {
            val result = try { api.repoSuggestions() } catch (e: RuntimeException) { null }
            val fresh = (result as? Outcome.Success)?.value ?: return@execute
            if (fresh == cached()) return@execute
            prefs.edit().putString(KEY, fresh.joinToString("\n")).apply()
            main.post { onLoaded(fresh) }
        }
    }

    private companion object {
        const val FILE = "repo_suggestions"
        const val KEY = "repos"
        val main = Handler(Looper.getMainLooper())
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "taskboard-repos").apply { isDaemon = true } }
    }
}
