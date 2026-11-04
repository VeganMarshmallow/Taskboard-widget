package io.github.veganmarshmallow.taskboard.core.mergeall

import io.github.veganmarshmallow.taskboard.core.work.crashError
import io.github.veganmarshmallow.taskboard.core.work.nonFatal
import java.time.Duration

/**
 * Drives a saved Merge all run (#57) for one background job: loads it, takes steps ([MergeAllMachine])
 * and saves after each, until the run ends, a step asks to wait, or [budget] is used up. The job then
 * schedules itself again after [Result.next]. A step that throws (a bug) stops the run with an error
 * rather than leaving it stuck. [onStep] sees every saved state (the widget redraws its progress).
 */
class MergeAllRunner(
    private val machine: MergeAllMachine,
    private val store: MergeAllStore,
    private val budget: Duration = BUDGET,
    private val ticker: () -> Long = System::nanoTime,
    private val onStep: (MergeAllState) -> Unit = {},
) {
    /** [state]: the run after this job (null: none saved). [next]: when to go on; null once it has ended. */
    data class Result(val state: MergeAllState?, val next: Duration?)

    fun run(): Result {
        var state = store.load() ?: return Result(null, null)
        val started = ticker()
        while (state.running) {
            if (Duration.ofNanos(ticker() - started) >= budget) return Result(state, RESUME)
            val step = try {
                machine.step(state, store.cancelRequested(state.runId))
            } catch (t: Throwable) {
                MergeAllMachine.Step(state.stopped(MergeAllStops.of(crashError(nonFatal(t))), state.updatedAt))
            }
            state = step.state
            store.save(state)
            onStep(state)
            if (state.running && step.waitFor != null) return Result(state, step.waitFor)
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
