package io.github.veganmarshmallow.taskboard.setup

import android.os.Handler
import android.os.Looper
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.data.AppGraph
import java.util.concurrent.Executors

/**
 * Runs "Test connection" off the main thread and delivers the result on the main thread.
 * [apiFor] builds the API for a token; tests pass a fake to avoid the network.
 */
class ConnectionTester(private val apiFor: (String) -> GitHubApi = AppGraph::api) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun test(token: String, onResult: (Outcome<Unit>) -> Unit) {
        executor.execute {
            val result = apiFor(token).testConnection()
            main.post { onResult(result) }
        }
    }

    fun shutdown() = executor.shutdownNow()
}
