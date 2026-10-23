package io.github.veganmarshmallow.taskboard.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.veganmarshmallow.taskboard.core.update.AvailableUpdate
import io.github.veganmarshmallow.taskboard.core.update.UpdateError
import io.github.veganmarshmallow.taskboard.core.update.UpdatePresenter.Activity
import io.github.veganmarshmallow.taskboard.core.update.UpdateResult
import io.github.veganmarshmallow.taskboard.core.update.UpdateStatus
import io.github.veganmarshmallow.taskboard.core.update.Updater
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.net.UrlConnectionStreamingClient
import io.github.veganmarshmallow.taskboard.widget.BackgroundWork
import io.github.veganmarshmallow.taskboard.widget.StateRedraw
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs update checks and downloads on their own thread (never the widget's refresh thread), keeps
 * the current [Activity] across screen rotations, and tells the visible screen about changes on
 * the main thread. Process-wide, because a download should survive the activity being recreated.
 */
object UpdateController {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "taskboard-update").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var listener: (() -> Unit)? = null

    /** What the update section is doing. Only read and written on the main thread. */
    var activity: Activity = Activity.Idle
        private set

    /**
     * Set when the user was sent to the "install unknown apps" setting; the update continues on
     * return. Kept here, not in the activity, so it survives the activity being recreated.
     */
    var waitingForPermission = false

    /** Called with the downloaded update when it is ready for the installer. Main thread. */
    var onReadyToInstall: ((AvailableUpdate) -> Unit)? = null

    fun listen(onChange: (() -> Unit)?) {
        listener = onChange
    }

    fun isBusy() = activity is Activity.Checking || activity is Activity.Downloading

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

    fun download(context: Context) {
        if (isBusy()) return
        val app = context.applicationContext
        val known = UpdateStore(app).load().available?.version
        set(Activity.Downloading(known, null))
        executor.execute {
            val result = downloadNow(app)
            main.post {
                when (result) {
                    is UpdateResult.Ok -> {
                        set(Activity.ReadyToInstall(result.value.version))
                        onReadyToInstall?.invoke(result.value)
                    }
                    is UpdateResult.Err -> set(Activity.Failed(result.error))
                }
            }
        }
    }

    /** Shows [error] (e.g. no app could install) until the next action. Main thread. */
    fun fail(error: UpdateError) = set(Activity.Failed(error))

    fun reset() = set(Activity.Idle)

    /** Checks and caches the result. Without a token nothing is cached: it isn't a real check. */
    private fun runCheck(app: Context): UpdateError? {
        val store = UpdateStore(app)
        val result = updater(app)?.check() ?: return UpdateError.NoToken
        val before = store.load()
        val after = before.withResult(result, AppGraph.clock.instant())
        store.save(after)
        if (badgeChanged(app, before, after)) BackgroundWork.run(null) { StateRedraw.redraw(app) }
        return (result as? UpdateResult.Err)?.error
    }

    private fun downloadNow(app: Context): UpdateResult<AvailableUpdate> {
        val updater = updater(app) ?: return UpdateResult.Err(UpdateError.NoToken)
        val installed = InstalledVersion.of(app).versionCode
        var lastPercent = -1
        val target = CacheDownloadTarget.of(app)
        val result = updater.downloadLatest(installed, target, ApkPackageCheck(app, target.part), { progress ->
            if (progress.percent != lastPercent) { // one UI update per percent, not per 64 KB chunk
                lastPercent = progress.percent
                main.post { (activity as? Activity.Downloading)?.let { set(Activity.Downloading(it.version, progress)) } }
            }
        })
        if (result is UpdateResult.Ok) {
            UpdateStore(app).save(UpdateStatus(AppGraph.clock.instant(), result.value, null))
        }
        return result
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
