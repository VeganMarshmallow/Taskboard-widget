package io.github.veganmarshmallow.taskboard.data

import android.content.Context
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.net.UrlConnectionHttpClient
import java.time.Clock

/** Wires core classes to Android implementations. The only place that knows both. */
object AppGraph {
    val clock: Clock = Clock.systemUTC()

    fun api(token: String): GitHubApi = GitHubApi(UrlConnectionHttpClient(), token, clock)

    /** Null when no token is saved. */
    fun apiOrNull(context: Context): GitHubApi? = TokenStore(context).get()?.let(::api)
}
