package io.github.veganmarshmallow.taskboard.data

import android.content.Context
import io.github.veganmarshmallow.taskboard.core.error.ErrorMessages
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.update.UpdateSchedule
import io.github.veganmarshmallow.taskboard.net.UrlConnectionHttpClient
import io.github.veganmarshmallow.taskboard.widget.TimeText
import java.time.Clock
import java.time.Duration

/** Wires core classes to Android implementations. The only place that knows both. */
object AppGraph {
    val clock: Clock = Clock.systemUTC()

    /** Background update checks (during widget refresh): at most once a day. */
    val updateSchedule = UpdateSchedule(Duration.ofDays(1))

    /** The check when the app opens is skipped right after another one (e.g. a screen rotation). */
    val updateOnOpen = UpdateSchedule(Duration.ofMinutes(1))

    fun api(token: String): GitHubApi = GitHubApi(UrlConnectionHttpClient(), token, clock)

    /** User-facing error text, with times in the device's zone and 12/24-hour setting. */
    fun messages(context: Context): ErrorMessages = ErrorMessages(TimeText(context)::format)

    /** Null when no token is saved. */
    fun apiOrNull(context: Context): GitHubApi? = TokenStore(context).get()?.let(::api)
}
