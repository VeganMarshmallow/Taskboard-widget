package io.github.veganmarshmallow.taskboard.core.work

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState

/** What asked for a refresh. */
enum class RefreshTrigger {
    /** The refresh button, an error-banner tap, a saved token: someone is waiting for it. */
    MANUAL,

    /** The widget's periodic update or its placement. */
    AUTOMATIC,
}

/** The small decisions around scheduling refreshes. */
object RefreshPolicy {
    /**
     * A manual refresh always goes ahead, even over a running one (which may be stuck): the newer
     * run wins. An automatic one never interrupts a live run, but does replace a dead (stale) one.
     */
    fun shouldSchedule(trigger: RefreshTrigger, status: RefreshTracker.Status): Boolean =
        trigger == RefreshTrigger.MANUAL || status != RefreshTracker.Status.RUNNING

    /**
     * Retry with the scheduler's backoff only for automatic refreshes whose failure can fix itself.
     * A manual refresh shows its error, and the user decides whether to tap again.
     */
    fun shouldRetry(trigger: RefreshTrigger, state: WidgetState): Boolean =
        trigger == RefreshTrigger.AUTOMATIC && when (state.error) {
            BoardError.Network, BoardError.Timeout, is BoardError.Server -> true
            else -> false
        }
}
