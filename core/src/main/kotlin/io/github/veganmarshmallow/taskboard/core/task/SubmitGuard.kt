package io.github.veganmarshmallow.taskboard.core.task

import java.util.concurrent.atomic.AtomicBoolean

/** Lets one submission run at a time, so a double tap can't create the same issue twice. */
class SubmitGuard {
    private val busy = AtomicBoolean(false)

    val isBusy: Boolean get() = busy.get()

    /** True if the caller may start a submission; false if one is already in flight. */
    fun tryStart(): Boolean = busy.compareAndSet(false, true)

    fun finish() = busy.set(false)
}
