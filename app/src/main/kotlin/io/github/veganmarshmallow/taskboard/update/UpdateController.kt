package io.github.veganmarshmallow.taskboard.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.veganmarshmallow.taskboard.core.update.UpdateError
import io.github.veganmarshmallow.taskboard.core.update.UpdatePresenter.Activity
import io.github.veganmarshmallow.taskboard.core.update.UpdateResult
import io.github.veganmarshmallow.taskboard.core.update.UpdateStatus
import io.github.veganmarshmallow.taskboard.core.update.Updater
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.net.UrlConnectionStreamingClient
import io.github.veganmarshmallow.taskboard.widget.StateRedraw
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs update checks on their own thread (never the widget's refresh thread), keeps the current
 * [Activity] across screen rotations, and tells the visible screen about changes on the main
 * thread. Process-wide, because a check should survive the activity being recreated.
 */
object UpdateController {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "taskboard-update").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var listener: (() -> Unit)? = null

    /** What the update section is doing. Only read and written on the main thread. */
    var activity: Activity = Activity.Idle
        private set

    fun listen(onChange: (() -> Unit)?) {
        listener = onChange
    }

    fun isBusy() = activity is Activity.Checking

    /** A user-visible check (the button, or app open via [checkOnOpen]). */
    fun check(context: Context) {
        if (isBusy()) return
        val app = context.applicationContext
        set(Activity.Checking)
        executor.execute {
            val error = runCheck(app)
            main.post { set(if (error == UpdateError.NoToken) Activity.Failed(error) else Activity.Idle) }
        }
    }

    /** The check when the app opens, skipped if one ran in the last minute (e.g. a screen rotation). */
    fun checkOnOpen(context: Context) {
        if (!TokenStore(context).hasToken()) return // the token card above already says what to do
        if (AppGraph.updateOnOpen.isDue(UpdateStore(context).load(), AppGraph.clock.instant())) check(context)
    }

    /**
     * Background check during a widget refresh: once a day, or an hour after a failed check (e.g. one
     * that ran offline). Silent, never on the refresh thread.
     */
    fun checkInBackgroundIfDue(context: Context) {
        val app = context.applicationContext
        executor.execute {
            if (AppGraph.updateSchedule.isDue(UpdateStore(app).load(), AppGraph.clock.instant())) runCheck(app)
        }
    }

    /** Shows [error] (e.g. no browser could open the release page) until the next action. Main thread. */
    fun fail(error: UpdateError) = set(Activity.Failed(error))

    fun reset() = set(Activity.Idle)

    /** Checks and caches the result. Without a token nothing is cached: it isn't a real check. */
    private fun runCheck(app: Context): UpdateError? {
        val store = UpdateStore(app)
        val result = updater(app)?.check() ?: return UpdateError.NoToken
        val before = store.load()
        val after = before.withResult(result, AppGraph.clock.instant())
        store.save(after)
        if (badgeChanged(app, before, after)) StateRedraw.redraw(app) // local only; already off the main thread
        return (result as? UpdateResult.Err)?.error
    }

    private fun badgeChanged(app: Context, before: UpdateStatus, after: UpdateStatus): Boolean {
        val installed = InstalledVersion.of(app).versionCode
        return (before.offer(installed) != null) != (after.offer(installed) != null)
    }

    private fun updater(app: Context): Updater? =
        TokenStore(app).get()?.let { Updater(UrlConnectionStreamingClient(), it, AppGraph.clock) }

    private fun set(next: Activity) {
        activity = next
        listener?.invoke()
    }
}
