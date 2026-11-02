package io.github.veganmarshmallow.taskboard.core.work

import io.github.veganmarshmallow.taskboard.core.http.Outcome

/**
 * Runs [work] and turns anything it throws into a [crashError] failure, so a bug or an odd platform
 * exception on a background thread is reported like any other failure instead of crashing the app or
 * leaving a screen stuck. Fatal VM errors (out of memory, stack overflow) are rethrown. An
 * [InterruptedException] becomes a failure with the thread's interrupt flag restored. The error names
 * only the exception's type, never its message, so no token can leak into it.
 */
inline fun <T> guarded(work: () -> Outcome<T>): Outcome<T> = try {
    work()
} catch (t: Throwable) {
    Outcome.Failure(crashError(nonFatal(t)))
}

/** Returns [t] if it can be reported; rethrows a [VirtualMachineError]; restores the interrupt flag. */
fun nonFatal(t: Throwable): Throwable {
    if (t is VirtualMachineError) throw t
    if (t is InterruptedException) Thread.currentThread().interrupt()
    return t
}
