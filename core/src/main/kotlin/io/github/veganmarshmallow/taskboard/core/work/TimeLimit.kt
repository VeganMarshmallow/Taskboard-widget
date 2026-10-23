package io.github.veganmarshmallow.taskboard.core.work

import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** How a time-limited piece of work ended. */
sealed class Timed<out T> {
    data class Done<T>(val value: T) : Timed<T>()

    /** The work threw, including [Error]s such as NoSuchMethodError. */
    data class Crashed(val error: Throwable) : Timed<Nothing>()

    /** The work did not finish in time. It is abandoned: its daemon thread is interrupted and its result ignored. */
    data object TimedOut : Timed<Nothing>()
}

/**
 * Runs blocking work on its own thread and waits for it at most a given time. Socket timeouts
 * only bound each connect or read, not a whole refresh: a response that trickles in, a slow DNS
 * lookup, or many pages can go on for minutes. This is what guarantees that a refresh ends.
 */
class TimeLimit(private val start: (Runnable) -> Unit = ::startDaemon) {
    fun <T> run(limit: Duration, work: () -> T): Timed<T> {
        val task = FutureTask<T> { work() }
        start(task)
        return try {
            Timed.Done(task.get(limit.toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS))
        } catch (e: TimeoutException) {
            task.cancel(true)
            Timed.TimedOut
        } catch (e: ExecutionException) {
            Timed.Crashed(e.cause ?: e)
        } catch (e: CancellationException) {
            Timed.TimedOut
        } catch (e: InterruptedException) {
            task.cancel(true)
            Thread.currentThread().interrupt()
            Timed.TimedOut
        }
    }

    private companion object {
        fun startDaemon(r: Runnable) {
            Thread(r, "taskboard-timed").apply { isDaemon = true }.start()
        }
    }
}
