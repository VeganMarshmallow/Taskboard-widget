package io.github.veganmarshmallow.taskboard.core.work

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coalesces refresh requests (refresh taps, periodic updates, token saves) so they never pile up
 * behind a slow refresh: at most one refresh waits behind the one that is running. A request made
 * while one is already waiting is redundant, because the waiting one will see the newest state.
 */
class RefreshGate {
    private val waiting = AtomicBoolean(false)

    /** True if the caller should queue a refresh; false if one is already waiting (drop this one). */
    fun request(): Boolean = waiting.compareAndSet(false, true)

    /** The waiting refresh has started running; the next request queues a new one. */
    fun started() = waiting.set(false)
}
