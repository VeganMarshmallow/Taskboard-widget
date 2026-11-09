package io.github.veganmarshmallow.taskboard

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

/**
 * Polls [condition] every 50 ms until it holds, failing with "timed out: [what]" once [timeoutMs] has passed.
 *
 * With [onMainThread] false (the default) the condition runs on the test thread and the main looper is
 * drained (`waitForIdleSync`) between checks. With it true the condition runs on the main thread through
 * `runOnMainSync`, for conditions that read views or other main-thread-only state.
 */
fun waitUntil(what: String, timeoutMs: Long = 10_000, onMainThread: Boolean = false, condition: () -> Boolean) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (!holds(onMainThread, condition)) {
        assertTrue("timed out: $what", SystemClock.uptimeMillis() < deadline)
        SystemClock.sleep(POLL_MS)
        if (!onMainThread) instrumentation.waitForIdleSync()
    }
}

private const val POLL_MS = 50L

private fun holds(onMainThread: Boolean, condition: () -> Boolean): Boolean {
    if (!onMainThread) return condition()
    var met = false
    InstrumentationRegistry.getInstrumentation().runOnMainSync { met = condition() }
    return met
}
