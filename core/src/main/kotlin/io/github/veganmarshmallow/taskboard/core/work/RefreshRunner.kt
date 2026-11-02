package io.github.veganmarshmallow.taskboard.core.work

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.snapshot.ShownStateStore
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import java.time.Clock
import java.time.Duration

/**
 * Runs one refresh so that it always ends in the new board or an error within [budget]: mark the
 * run as started, load under a [TimeLimit], and save the result if this run is still the newest.
 * A run that died with its process is cleaned up later by [recoverIfStale].
 *
 * A save that fails is shown through [states] ([ShownStateStore.showUnsaved]), inside the tracker's
 * lock, so the store always holds what the widget should show and every render can read it there.
 */
class RefreshRunner(
    private val tracker: RefreshTracker,
    private val states: ShownStateStore,
    private val clock: Clock,
    private val limit: TimeLimit = TimeLimit(),
    private val budget: Duration = RUN_BUDGET,
) {
    sealed class Result {
        /** This run ended; [state] is what the store now shows (the new board, or the save failure). */
        data class Committed(val state: WidgetState) : Result()

        /** A newer run started meanwhile, so this run's result was dropped. */
        data object Superseded : Result()
    }

    /**
     * [load] turns the previous state into the next one (fetch and build). [onStarted] runs right
     * after the run is marked, e.g. to show "updating…"; if it fails, the run still goes ahead.
     * Never throws: a failure to read or save the stored state also ends in an error state, so
     * the caller always has something to render in place of "updating…".
     */
    fun run(onStarted: () -> Unit = {}, load: (WidgetState) -> WidgetState): Result {
        val mark = tracker.begin()
        try {
            onStarted()
        } catch (e: RuntimeException) {
            // Cosmetic only: the run's result is what matters.
        }
        val previous = loadOrInitial()
        val next = when (val r = limit.run(budget) { load(previous) }) {
            is Timed.Done -> r.value
            is Timed.Crashed -> previous.withError(crashError(r.error), clock.instant())
            Timed.TimedOut -> previous.withError(BoardError.Timeout, clock.instant())
        }
        return try {
            val committed = tracker.commitIfCurrent(mark.runId) {
                saveOrShow(next) { t -> previous.withError(crashError(t), clock.instant()) }
            }
            if (committed) Result.Committed(next) else Result.Superseded
        } catch (t: Throwable) {
            // The save or clearing the mark failed; the tracker has ended the run. Either way the
            // store now shows the right thing: the saved board, or the save failure.
            Result.Committed(loadOrInitial())
        }
    }

    /**
     * Saves [state]; if that fails, shows [onFailure]'s state instead and rethrows. Called inside
     * the tracker's lock, so a newer run's save can never land before the failure is shown.
     */
    private fun saveOrShow(state: WidgetState, onFailure: (Throwable) -> WidgetState) {
        try {
            states.save(state)
        } catch (t: Throwable) {
            states.showUnsaved(onFailure(t))
            throw t
        }
    }

    /**
     * If a run died (its mark went stale): records a timeout (shown even if saving it fails) and
     * returns it. Otherwise null (so a sweep after a good refresh never touches the good board). Never throws.
     */
    fun recoverIfStale(): WidgetState? {
        var recovered: WidgetState? = null
        try {
            tracker.clearIfStale {
                val state = loadOrInitial().withError(BoardError.Timeout, clock.instant())
                recovered = state
                saveOrShow(state) { state }
            }
        } catch (t: Throwable) {
            // Saving failed; the mark is cleared anyway, and the store shows the error over "updating…".
        }
        return recovered
    }

    private fun loadOrInitial(): WidgetState = try {
        states.load()
    } catch (t: Throwable) {
        WidgetState.INITIAL
    }

    companion object {
        /** The whole-refresh limit. Below [RefreshTracker.STALE_AFTER], so a live run is never taken for a dead one. */
        val RUN_BUDGET: Duration = Duration.ofSeconds(50)
    }
}
