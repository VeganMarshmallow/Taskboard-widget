package io.github.veganmarshmallow.taskboard.core.work

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetStateStore
import java.time.Clock
import java.time.Duration

/**
 * Runs one refresh so that it always ends in the new board or an error within [budget]: mark the
 * run as started, load under a [TimeLimit], and save the result if this run is still the newest.
 * A run that died with its process is cleaned up later by [recoverIfStale].
 */
class RefreshRunner(
    private val tracker: RefreshTracker,
    private val states: WidgetStateStore,
    private val clock: Clock,
    private val limit: TimeLimit = TimeLimit(),
    private val budget: Duration = RUN_BUDGET,
) {
    sealed class Result {
        /** This run's state was saved; render it. */
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
            if (tracker.commitIfCurrent(mark.runId) { states.save(next) }) Result.Committed(next) else Result.Superseded
        } catch (t: Throwable) {
            // The save (or clearing the mark) failed. The tracker has ended the run; show the failure.
            Result.Committed(previous.withError(crashError(t), clock.instant()))
        }
    }

    /**
     * If a run died (its mark went stale): records a timeout and returns the state to render.
     * Otherwise null (so a sweep after a good refresh never touches the good board). Never throws.
     */
    fun recoverIfStale(): WidgetState? {
        var recovered: WidgetState? = null
        try {
            tracker.clearIfStale {
                val state = loadOrInitial().withError(BoardError.Timeout, clock.instant())
                recovered = state
                states.save(state)
            }
        } catch (t: Throwable) {
            // Saving failed; the mark is cleared anyway. Still render the error over "updating…".
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
