package io.github.veganmarshmallow.taskboard.data

import android.content.Context
import io.github.veganmarshmallow.taskboard.core.error.ErrorMessages
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.net.UrlConnectionHttpClient
import io.github.veganmarshmallow.taskboard.widget.TimeText
import java.time.Clock

/** Wires core classes to Android implementations. The only place that knows both. */
object AppGraph {
    val clock: Clock = Clock.systemUTC()

    fun api(token: String): GitHubApi = GitHubApi(UrlConnectionHttpClient(), token, clock)

    /** User-facing error text, with times in the device's zone and 12/24-hour setting. */
    fun messages(context: Context): ErrorMessages = ErrorMessages(TimeText(context)::format)

    /** Null when no token is saved. */
    fun apiOrNull(context: Context): GitHubApi? = TokenStore(context).get()?.let(::api)
}
