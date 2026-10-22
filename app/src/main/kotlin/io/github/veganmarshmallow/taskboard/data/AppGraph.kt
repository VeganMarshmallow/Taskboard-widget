package io.github.veganmarshmallow.taskboard.data

import android.content.Context
import io.github.veganmarshmallow.taskboard.core.error.ErrorMessages
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.HttpClient
import io.github.veganmarshmallow.taskboard.core.work.RefreshRunner
import io.github.veganmarshmallow.taskboard.core.work.RefreshTracker
import io.github.veganmarshmallow.taskboard.net.UrlConnectionHttpClient
import io.github.veganmarshmallow.taskboard.widget.TimeText
import java.time.Clock

/** Wires core classes to Android implementations. The only place that knows both. */
object AppGraph {
    val clock: Clock = Clock.systemUTC()

    /** Swapped only by instrumented tests (a slow local server). */
    @Volatile var httpClient: () -> HttpClient = { UrlConnectionHttpClient() }

    fun api(token: String): GitHubApi = GitHubApi(httpClient(), token, clock)

    @Volatile private var tracker: RefreshTracker? = null

    /** One per process, so its lock really serializes every refresh state change. */
    fun refreshTracker(context: Context): RefreshTracker = tracker ?: synchronized(this) {
        tracker ?: RefreshTracker(RefreshMarkPrefs(context), clock).also { tracker = it }
    }

    fun refreshRunner(context: Context): RefreshRunner = RefreshRunner(refreshTracker(context), StateStore(context), clock)

    /** User-facing error text, with times in the device's zone and 12/24-hour setting. */
    fun messages(context: Context): ErrorMessages = ErrorMessages(TimeText(context)::format)

    /** Null when no token is saved. */
    fun apiOrNull(context: Context): GitHubApi? = TokenStore(context).get()?.let(::api)
}
