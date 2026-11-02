package io.github.veganmarshmallow.taskboard.newtask

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.task.NewTaskStore
import io.github.veganmarshmallow.taskboard.data.NewTaskPrefs
import java.time.Clock
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * What the New task form needs from the outside: GitHub, threads, where the draft and submit
 * marker are kept ([store]) and the time a submit starts ([clock]).
 * [io.github.veganmarshmallow.taskboard.data.AppGraph] builds the real ones; instrumented tests build their own with a fake GitHub.
 *
 * Sending and loading suggestions use separate threads, so a slow suggestions request never
 * delays a submit.
 */
class NewTaskDeps(
    /** Null result = no token saved. */
    val apiProvider: (Context) -> GitHubApi?,
    val sendExecutor: Executor,
    val suggestionsExecutor: Executor,
    /** Runs results on the main thread. */
    val mainThread: Executor,
    val store: (Context) -> NewTaskStore = ::NewTaskPrefs,
    val clock: Clock = Clock.systemUTC(),
) {
    companion object {
        /** Real threads: one background thread each for sending and suggestions, plus the main looper. */
        fun standard(apiProvider: (Context) -> GitHubApi?): NewTaskDeps = NewTaskDeps(
            apiProvider,
            sendExecutor = backgroundThread("taskboard-new-task"),
            suggestionsExecutor = backgroundThread("taskboard-repos"),
            mainThread = mainThread(),
        )

        private fun backgroundThread(name: String): Executor =
            Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } }

        private fun mainThread(): Executor {
            val handler = Handler(Looper.getMainLooper())
            return Executor { handler.post(it) }
        }
    }
}
