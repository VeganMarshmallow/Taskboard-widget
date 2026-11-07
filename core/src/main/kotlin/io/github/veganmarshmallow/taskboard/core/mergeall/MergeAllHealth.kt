package io.github.veganmarshmallow.taskboard.core.mergeall

import java.time.Duration
import java.time.Instant

/**
 * Whether a saved Merge all run is still being driven (#78). Every poll saves the run, so a run
 * whose last save is older than [staleAfter] (3 polls) has stalled: its job was lost, or held back
 * by Android (no network for the app, battery limits). The widget then says "resuming" and the next
 * refresh or app open schedules it again.
 */
object MergeAllHealth {
    /** How many missed polls make a run stale. */
    const val STALE_POLLS = 3L

    fun staleAfter(poll: Duration): Duration = poll.multipliedBy(STALE_POLLS)

    val STALE_AFTER: Duration = staleAfter(MergeAllMachine.POLL)

    fun stale(state: MergeAllState?, now: Instant, after: Duration = STALE_AFTER): Boolean =
        state != null && state.running && Duration.between(state.updatedAt, now) > after

    /**
     * The run as a job picks it up after a stall: the stalled time was the app's, not GitHub's, so
     * it doesn't count toward the per-PR wait limit ([MergeAllMachine.CHECKS_TIMEOUT]).
     */
    fun resumed(state: MergeAllState, now: Instant, after: Duration = STALE_AFTER): MergeAllState {
        if (!stale(state, now, after)) return state
        val stalled = Duration.between(state.updatedAt, now)
        return state.copy(waitSince = state.waitSince?.plus(stalled))
    }

    /**
     * Cancel without waiting for a job (#78): a run that is between steps and not at its merge ends
     * now. At MERGE the job re-reads the PR first (a merge sent before a process death is counted),
     * so that one step still finishes. Null: cancel has to wait for the job.
     */
    fun cancelNow(state: MergeAllState?, now: Instant): MergeAllState? =
        state?.takeIf { it.running && it.phase != MergeAllPhase.MERGE }?.cancelled(now)
}
