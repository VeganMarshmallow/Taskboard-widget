package io.github.veganmarshmallow.taskboard.core.mergeall

import io.github.veganmarshmallow.taskboard.core.work.crashError
import io.github.veganmarshmallow.taskboard.core.work.nonFatal
import java.time.Duration

/**
 * Drives a saved Merge all run (#57) for one background job: loads it, takes steps ([MergeAllMachine])
 * and saves after each, until the run ends, a step asks to wait, or [budget] is used up. The job then
 * schedules itself again after [Result.next]. A step that throws (a bug) stops the run with an error
 * rather than leaving it stuck. [onStep] sees every saved state (the widget redraws its progress).
 *
 * #78: [online] is checked before each step (offline, the step waits without calling GitHub). A
 * Cancel tapped during a step is honoured right after it, not a poll later. A run reset or replaced
 * while a step was in flight is left alone: that step's result is dropped, not saved over it.
 */
class MergeAllRunner(
    private val machine: MergeAllMachine,
    private val store: MergeAllStore,
    private val budget: Duration = BUDGET,
    private val ticker: () -> Long = System::nanoTime,
    private val onStep: (MergeAllState) -> Unit = {},
    private val online: () -> Boolean = { true },
) {
    /** [state]: the run after this job (null: none saved). [next]: when to go on; null once it has ended. */
    data class Result(val state: MergeAllState?, val next: Duration?)

    fun run(): Result {
        var state = store.load() ?: return Result(null, null)
        val started = ticker()
        while (state.running) {
            if (Duration.ofNanos(ticker() - started) >= budget) return Result(state, RESUME)
            val step = try {
                machine.step(state, store.cancelRequested(state.runId), online())
            } catch (t: Throwable) {
                MergeAllMachine.Step(state.stopped(MergeAllStops.of(crashError(nonFatal(t))), state.updatedAt))
            }
            val saved = store.load()
            if (saved == null || saved.runId != state.runId || !saved.running) return Result(saved, null) // reset meanwhile
            state = step.state
            store.save(state)
            onStep(state)
            // Cancel tapped while this step ran: the next step cancels rather than waiting a poll
            // (at MERGE it re-reads the PR first, so that one waits its poll like any other).
            val cancelNext = state.phase != MergeAllPhase.MERGE && store.cancelRequested(state.runId)
            if (state.running && step.waitFor != null && !cancelNext) return Result(state, step.waitFor)
        }
        return Result(state, null)
    }

    companion object {
        /** Well inside JobScheduler's limit for one job; a longer run goes on in the next job. */
        val BUDGET: Duration = Duration.ofMinutes(4)

        /** After a job used its whole budget. */
        val RESUME: Duration = Duration.ofSeconds(1)
    }
}
