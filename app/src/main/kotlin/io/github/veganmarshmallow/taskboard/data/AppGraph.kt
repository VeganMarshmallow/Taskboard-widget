package io.github.veganmarshmallow.taskboard.data

import android.content.Context
import io.github.veganmarshmallow.taskboard.core.error.ErrorMessages
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.update.UpdateSchedule
import io.github.veganmarshmallow.taskboard.core.http.HttpClient
import io.github.veganmarshmallow.taskboard.core.work.RefreshRunner
import io.github.veganmarshmallow.taskboard.core.work.RefreshTracker
import io.github.veganmarshmallow.taskboard.core.work.RetryBackoff
import io.github.veganmarshmallow.taskboard.core.work.RetryPlanner
import io.github.veganmarshmallow.taskboard.net.UrlConnectionHttpClient
import io.github.veganmarshmallow.taskboard.newtask.NewTaskController
import io.github.veganmarshmallow.taskboard.newtask.NewTaskDeps
import io.github.veganmarshmallow.taskboard.widget.TimeText
import java.time.Clock
import java.time.Duration

/** Wires core classes to Android implementations. The only place that knows both. */
object AppGraph {
    val clock: Clock = Clock.systemUTC()

    /** Background update checks (during widget refresh): once a day, or an hour after a failed check. */
    val updateSchedule = UpdateSchedule(Duration.ofDays(1), Duration.ofHours(1))

    /** The check when the app opens is skipped right after another one (e.g. a screen rotation). */
    val updateOnOpen = UpdateSchedule(Duration.ofMinutes(1))

    /** Swapped only by instrumented tests (a slow local server). */
    @Volatile var httpClient: () -> HttpClient = { UrlConnectionHttpClient() }

    fun api(token: String): GitHubApi = GitHubApi(httpClient(), token, clock)

    @Volatile private var tracker: RefreshTracker? = null

    /** One per process, so its lock really serializes every refresh state change. */
    fun refreshTracker(context: Context): RefreshTracker = tracker ?: synchronized(this) {
        tracker ?: RefreshTracker(RefreshMarkPrefs(context), DeviceUptime(context)).also { tracker = it }
    }

    fun refreshRunner(context: Context): RefreshRunner = RefreshRunner(refreshTracker(context), StateStore(context), clock)

    /** Swapped only by instrumented tests (seconds instead of minutes). Setting it rebuilds the planner. */
    @Volatile var retryBackoff: RetryBackoff = RetryBackoff.DEFAULT
        set(value) = synchronized(this) {
            field = value
            planner = null
        }

    @Volatile private var planner: RetryPlanner? = null

    /** One per process, like the tracker, so the failure streak is updated under one lock. */
    fun retryPlanner(context: Context): RetryPlanner = planner ?: synchronized(this) {
        planner ?: RetryPlanner(RetryStreakPrefs(context), DeviceUptime(context), retryBackoff).also { planner = it }
    }

    /** User-facing error text, with times in the device's zone and 12/24-hour setting. */
    fun messages(context: Context): ErrorMessages = ErrorMessages(TimeText(context)::format)

    /** Null when no token is saved. */
    fun apiOrNull(context: Context): GitHubApi? = TokenStore(context).get()?.let(::api)

    /**
     * The New task form's controller. One per process, so the draft and an in-flight request
     * outlive the Activity (rotation). Replaced only by instrumented tests (`NewTaskRule`).
     */
    @Volatile var newTask: NewTaskController = NewTaskController(NewTaskDeps.standard(::apiOrNull))
}
