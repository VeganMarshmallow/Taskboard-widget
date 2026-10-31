package io.github.veganmarshmallow.taskboard.update

import android.os.Handler
import android.os.Looper
import io.github.veganmarshmallow.taskboard.core.update.UpdateError
import io.github.veganmarshmallow.taskboard.core.update.UpdateFlow
import io.github.veganmarshmallow.taskboard.core.update.UpdateFlow.Command
import io.github.veganmarshmallow.taskboard.core.update.UpdateFlow.Input
import io.github.veganmarshmallow.taskboard.core.update.UpdatePresenter.Activity
import io.github.veganmarshmallow.taskboard.core.update.Updater
import java.time.Clock
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs [UpdateFlow]'s commands: checks on its own thread (never the widget's refresh thread), keeps
 * the current [Activity] across screen rotations, and tells every listening screen about changes on
 * the main thread. One per process, created by `AppGraph`, so a check outlives the activity.
 */
class UpdateController(
    private val flow: UpdateFlow,
    private val store: UpdateStore,
    private val clock: Clock,
    private val hasToken: () -> Boolean,
    private val updater: () -> Updater?,
    private val installedVersionCode: () -> Long,
    private val redrawBadge: () -> Unit,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "taskboard-update").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** What the update section is doing. Only read and written on the main thread. */
    var activity: Activity = Activity.Idle
        private set

    fun addListener(onChange: () -> Unit) { listeners += onChange }

    fun removeListener(onChange: () -> Unit) { listeners -= onChange }

    /** The section was shown: clears a shown failure and checks unless one ran in the last minute. Main thread. */
    fun opened() = dispatch(Input.Opened(hasToken(), store.load(), clock.instant()))

    /** A user-visible check (the button, or a new token). Main thread. */
    fun check() = dispatch(Input.CheckRequested)

    /** Shows [error] (e.g. no browser could open the release page) until the next action. Main thread. */
    fun fail(error: UpdateError) = dispatch(Input.ReleasePageFailed(error))

    /** Background check during a widget refresh, when due. Silent, never on the refresh thread. */
    fun checkInBackgroundIfDue() {
        executor.execute { if (flow.backgroundCheckDue(store.load(), clock.instant())) runCheck() }
    }

    private fun dispatch(input: Input) {
        val step = flow.next(activity, input)
        if (step.activity != activity) {
            activity = step.activity
            listeners.forEach { it() }
        }
        when (step.command) {
            Command.RunCheck -> executor.execute {
                val error = runCheck()
                main.post { dispatch(Input.CheckFinished(error)) }
            }
            null -> Unit
        }
    }

    /** Checks and caches the result, and redraws the widget if its dot changed. Update thread. */
    private fun runCheck(): UpdateError? {
        val result = updater()?.check()
        val recorded = flow.record(store.load(), result, clock.instant(), installedVersionCode())
        recorded.status?.let(store::save)
        if (recorded.badgeChanged) redrawBadge() // local only; already off the main thread
        return recorded.error
    }
}
