package io.github.veganmarshmallow.taskboard.core.mergeall

import java.time.Instant

/**
 * The widget's Cancel and Reset on a saved run (#78), over its [store]. The app calls [cancel] with
 * `idle = true` only while no job step is running (it holds the job's lock), so the state it saves
 * can't be overwritten by a step in flight.
 */
class MergeAllControl(private val store: MergeAllStore, private val now: () -> Instant) {
    /** Asks the run to stop; when [idle] and it is waiting, it ends at once. The ended run, or null. */
    fun cancel(idle: Boolean): MergeAllState? {
        val state = store.load()?.takeIf { it.running } ?: return null
        store.requestCancel(state.runId)
        if (!idle) return null
        return MergeAllHealth.cancelNow(state, now())?.takeIf(store::saveIf) // a Reset meanwhile wins (#81)
    }

    /** The escape hatch: forgets the run, whatever state it is in. A step in flight drops its result ([MergeAllRunner]). */
    fun reset() = store.clear()
}
