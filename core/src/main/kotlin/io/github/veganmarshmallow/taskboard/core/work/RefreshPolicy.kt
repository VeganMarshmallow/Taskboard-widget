package io.github.veganmarshmallow.taskboard.core.work

import io.github.veganmarshmallow.taskboard.core.error.BoardError

/** What asked for a refresh. */
enum class RefreshTrigger {
    /** The refresh button, an error-banner tap, a saved token: someone is waiting for it. */
    MANUAL,

    /** The widget's periodic update or its placement. */
    AUTOMATIC,

    /** A scheduled retry after a failed refresh (see [RetryPlanner]). */
    RETRY,
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
     * Whether a refresh that ended with [error] should be retried automatically, whatever started
     * it: only for failures that can fix themselves (no network, GitHub unreachable or too slow, a
     * 5xx). Never for a success (null), token or access problems (401/403/404), rate limits (the
     * periodic update tries again after the limit), or unexpected responses.
     */
    fun shouldAutoRetry(error: BoardError?): Boolean = when (error) {
        BoardError.Network, BoardError.Unreachable, BoardError.Timeout, is BoardError.Server -> true
        else -> false
    }
}
