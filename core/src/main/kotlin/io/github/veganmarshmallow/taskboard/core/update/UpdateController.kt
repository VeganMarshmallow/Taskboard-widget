package io.github.veganmarshmallow.taskboard.core.update

import io.github.veganmarshmallow.taskboard.core.update.UpdateFlow.Command
import io.github.veganmarshmallow.taskboard.core.update.UpdateFlow.Input
import io.github.veganmarshmallow.taskboard.core.update.UpdatePresenter.Activity
import java.time.Clock
import java.util.concurrent.Executor

/**
 * Runs [UpdateFlow]'s commands: checks on [background] (never the widget's refresh thread), keeps
 * the current [Activity] across screen rotations, and tells every listening screen on [mainThread]
 * when the activity changes or a check cached a new status. One per process, created by the app's
 * `AppGraph` with real threads, so a check outlives the activity; tests pass synchronous fakes.
 *
 * Listeners: each is notified at most once per change, in the order added. One removed during a
 * notification (by itself or another listener) is not called after that; one added during a
 * notification is called from the next change on. If a listener throws, the rest are still
 * notified and the first exception is rethrown afterwards, so a bug still crashes visibly.
 */
class UpdateController(
    private val flow: UpdateFlow,
    private val store: UpdateStatusStore,
    private val clock: Clock,
    private val hasToken: () -> Boolean,
    /** Fetches the latest release; null when no token is saved. Runs on [background]. */
    private val fetchLatest: () -> UpdateResult<AvailableUpdate>?,
    private val installedVersionCode: () -> Long,
    private val redrawBadge: () -> Unit,
    private val background: Executor,
    /** Runs a block on the main thread (posts it; never runs it inline from [background]). */
    private val mainThread: (Runnable) -> Unit,
) {
    private val listeners = LinkedHashSet<() -> Unit>()

    /** Counts user-visible checks, so a finish from a superseded check doesn't end the newer one. */
    private var latestCheck = 0

    /** What the update section is doing. Only read and written on the main thread. */
    var activity: Activity = Activity.Idle
        private set

    /** Main thread. Adding the same listener twice keeps one. */
    fun addListener(onChange: () -> Unit) { listeners += onChange }

    /** Main thread. */
    fun removeListener(onChange: () -> Unit) { listeners -= onChange }

    /** The section was shown: clears a shown failure and checks unless one ran in the last minute. Main thread. */
    fun opened() = dispatch(Input.Opened(hasToken(), store.load(), clock.instant()))

    /** A user-visible check (the button, or a new token). Main thread. */
    fun check() = dispatch(Input.CheckRequested)

    /** Shows [error] (e.g. no browser could open the release page) until the next action. Main thread. */
    fun fail(error: UpdateError) = dispatch(Input.ReleasePageFailed(error))

    /** Background check during a widget refresh, when due. Silent, never on the refresh thread. */
    fun checkInBackgroundIfDue() {
        background.execute { if (flow.backgroundCheckDue(store.load(), clock.instant())) runCheck() }
    }

    private fun dispatch(input: Input) {
        val step = flow.next(activity, input)
        val changed = step.activity != activity || step.refresh
        activity = step.activity
        // Start the command before notifying, so a throwing listener can't leave Checking with no check.
        when (step.command) {
            Command.RunCheck -> startCheck()
            null -> Unit
        }
        if (changed) notifyListeners()
    }

    private fun startCheck() {
        val id = ++latestCheck
        background.execute {
            val error = runCheck()
            mainThread(Runnable { finished(id, error) })
        }
    }

    /**
     * A check ended. A superseded one (a failure was shown during it and the user started another)
     * only re-renders the status it cached; the newer check still owns [activity].
     */
    private fun finished(id: Int, error: UpdateError?) {
        if (id == latestCheck) dispatch(Input.CheckFinished(error)) else notifyListeners()
    }

    private fun notifyListeners() {
        var failure: Throwable? = null
        for (listener in listeners.toList()) {
            if (listener !in listeners) continue // removed by an earlier listener
            try {
                listener()
            } catch (e: Exception) {
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
        }
        if (failure != null) throw failure
    }

    /** Checks and caches the result, and redraws the widget if its dot changed. Background thread. */
    private fun runCheck(): UpdateError? {
        val recorded = flow.record(store.load(), fetchLatest(), clock.instant(), installedVersionCode())
        recorded.status?.let(store::save)
        if (recorded.badgeChanged) redrawBadge() // local only; already off the main thread
        return recorded.error
    }
}
