package io.github.veganmarshmallow.taskboard.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.veganmarshmallow.taskboard.core.error.ErrorMessages
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.HttpClient
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStore
import io.github.veganmarshmallow.taskboard.core.snapshot.ShownStateStore
import io.github.veganmarshmallow.taskboard.core.update.UpdateController
import io.github.veganmarshmallow.taskboard.core.update.UpdateFlow
import io.github.veganmarshmallow.taskboard.core.update.UpdateSchedule
import io.github.veganmarshmallow.taskboard.core.update.Updater
import io.github.veganmarshmallow.taskboard.core.work.RefreshRunner
import io.github.veganmarshmallow.taskboard.core.work.RefreshTracker
import io.github.veganmarshmallow.taskboard.core.work.RetryBackoff
import io.github.veganmarshmallow.taskboard.core.work.RetryPlanner
import io.github.veganmarshmallow.taskboard.net.UrlConnectionHttpClient
import io.github.veganmarshmallow.taskboard.net.UrlConnectionStreamingClient
import io.github.veganmarshmallow.taskboard.newtask.NewTaskController
import io.github.veganmarshmallow.taskboard.newtask.NewTaskDeps
import io.github.veganmarshmallow.taskboard.update.InstalledVersion
import io.github.veganmarshmallow.taskboard.update.UpdateStore
import io.github.veganmarshmallow.taskboard.widget.ConnectivityProbe
import io.github.veganmarshmallow.taskboard.widget.StateRedraw
import io.github.veganmarshmallow.taskboard.widget.TimeText
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors

/** Wires core classes to Android implementations. The only place that knows both. */
object AppGraph {
    val clock: Clock = Clock.systemUTC()

    private fun httpClient(): HttpClient = TestOverrides.httpClient?.invoke() ?: UrlConnectionHttpClient()

    /** Every request's connection failure is worded from the device's network state (#35). */
    fun api(context: Context, token: String): GitHubApi =
        GitHubApi(httpClient(), token, clock, probe = ConnectivityProbe(context))

    @Volatile private var tracker: RefreshTracker? = null

    /** One per process, so its lock really serializes every refresh state change. */
    fun refreshTracker(context: Context): RefreshTracker = tracker ?: synchronized(this) {
        tracker ?: RefreshTracker(RefreshMarkPrefs(context), DeviceUptime(context)).also { tracker = it }
    }

    @Volatile private var states: ShownStateStore? = null

    /** One per process, so a failed save's error (kept in memory) is seen by every reader (#37). */
    fun shownStates(context: Context): ShownStateStore = states ?: synchronized(this) {
        states ?: ShownStateStore(StatePrefs(context)).also { states = it }
    }

    fun refreshRunner(context: Context): RefreshRunner = RefreshRunner(refreshTracker(context), shownStates(context), clock)

    /** The planner and the backoff it was built with (a test override of the backoff rebuilds it). */
    @Volatile private var planner: Pair<RetryBackoff, RetryPlanner>? = null

    /** One per process, like the tracker, so the failure streak is updated under one lock. */
    fun retryPlanner(context: Context): RetryPlanner {
        val backoff = TestOverrides.retryBackoff ?: RetryBackoff.DEFAULT
        planner?.let { (built, current) -> if (built === backoff) return current }
        return synchronized(this) {
            planner?.takeIf { it.first === backoff }?.second
                ?: RetryPlanner(RetryStreakPrefs(context), DeviceUptime(context), backoff).also { planner = backoff to it }
        }
    }

    /** User-facing error text, with times in the device's zone and 12/24-hour setting. */
    fun messages(context: Context): ErrorMessages = ErrorMessages(TimeText(context)::format)

    /** The Merge all run's store (#57). */
    fun mergeAllStore(context: Context): MergeAllStore = MergeAllPrefs.store(context)

    /** Null when no token is saved. */
    fun apiOrNull(context: Context): GitHubApi? = TokenStore(context).get()?.let { api(context, it) }

    /**
     * The New task form's controller. One per process, so the draft and an in-flight request
     * outlive the Activity (rotation). Debug builds let instrumented tests override it ([TestOverrides]).
     */
    val newTask: NewTaskController get() = TestOverrides.newTask ?: appNewTask

    private val appNewTask = NewTaskController(NewTaskDeps.standard(::apiOrNull))

    @Volatile private var updateController: UpdateController? = null

    /** The update check's controller. One per process, so a check outlives the Activity (rotation). */
    fun updates(context: Context): UpdateController = updateController ?: synchronized(this) {
        updateController ?: newUpdateController(context.applicationContext).also { updateController = it }
    }

    private fun newUpdateController(app: Context) = UpdateController(
        // Opening the app checks unless one ran a minute ago (a rotation); a widget refresh checks
        // once a day, or an hour after a failed check.
        flow = UpdateFlow(onOpen = UpdateSchedule(Duration.ofMinutes(1)), background = UpdateSchedule(Duration.ofDays(1), Duration.ofHours(1))),
        store = UpdateStore(app),
        clock = clock,
        hasToken = { TokenStore(app).hasToken() },
        fetchLatest = { TokenStore(app).get()?.let { Updater(UrlConnectionStreamingClient(), it, clock, ConnectivityProbe(app)).check() } },
        installedVersionCode = { InstalledVersion.of(app).versionCode },
        redrawBadge = { StateRedraw.redraw(app) },
        background = Executors.newSingleThreadExecutor { r -> Thread(r, "taskboard-update").apply { isDaemon = true } },
        mainThread = Handler(Looper.getMainLooper()).let { main -> { main.post(it) } },
    )
}
