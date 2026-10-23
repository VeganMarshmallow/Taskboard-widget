package io.github.veganmarshmallow.taskboard.core.work

import io.github.veganmarshmallow.taskboard.core.work.UptimeInstant.Companion.UNKNOWN_BOOT
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * The refresh state machine: IDLE → RUNNING ([begin]) → IDLE ([commitIfCurrent], or [clearIfStale]
 * once the run is presumed dead). A mark is STALE (that run died and will never clear its own
 * mark) when it is older than [staleAfter], or comes from another boot (the device restarted
 * mid-refresh). Age is measured on the monotonic [Uptime] clock, so wall-clock jumps in either
 * direction neither kill a live run nor keep a dead one alive.
 *
 * Only the newest run may commit. A run that was replaced (a refresh tap while another one hung)
 * drops its late result instead of overwriting newer data or clearing the newer run's mark.
 *
 * Use one instance per process, so [lock] serializes every transition.
 */
class RefreshTracker(
    private val store: RefreshMarkStore,
    private val uptime: Uptime,
    private val staleAfter: Duration = STALE_AFTER,
    private val nextId: () -> Long = randomlySeededIds(),
) {
    enum class Status { IDLE, RUNNING, STALE }

    private val lock = Any()

    init {
        require(!staleAfter.isNegative && !staleAfter.isZero) { "staleAfter must be positive" }
    }

    fun status(): Status = synchronized(lock) { statusOf(store.load()) }

    /** Whether to show "updating…": only for a live run, never for a dead one. */
    fun isUpdating(): Boolean = status() == Status.RUNNING

    /** Starts a run, replacing any other (live or dead) run's mark. */
    fun begin(): RefreshMark = synchronized(lock) {
        RefreshMark(nextId(), uptime.now()).also(store::save)
    }

    /**
     * If [runId] is still the current run: runs [commit], then ends the run (the mark is cleared
     * even when [commit] throws). Returns false, without calling [commit], if a newer run took over.
     */
    fun commitIfCurrent(runId: Long, commit: () -> Unit): Boolean = synchronized(lock) {
        if (store.load()?.runId != runId) return false
        try {
            commit()
        } finally {
            store.save(null)
        }
        true
    }

    /** If the current run is stale: runs [recover], then clears the mark. Returns whether it did. */
    fun clearIfStale(recover: () -> Unit): Boolean = synchronized(lock) {
        if (statusOf(store.load()) != Status.STALE) return false
        try {
            recover()
        } finally {
            store.save(null)
        }
        true
    }

    private fun statusOf(mark: RefreshMark?): Status {
        mark ?: return Status.IDLE
        val now = uptime.now()
        val bootsKnown = mark.startedAt.boot != UNKNOWN_BOOT && now.boot != UNKNOWN_BOOT
        if (bootsKnown && mark.startedAt.boot != now.boot) return Status.STALE
        val ageMs = now.elapsedMs - mark.startedAt.elapsedMs
        return when {
            ageMs < 0 -> Status.STALE // the uptime clock restarted: a reboot the boot count missed
            ageMs > staleAfter.toMillis() -> Status.STALE
            else -> Status.RUNNING
        }
    }

    companion object {
        /** After this long a run is presumed dead. Longer than [RefreshRunner.RUN_BUDGET], so a live run always ends first. */
        val STALE_AFTER: Duration = Duration.ofSeconds(60)

        /** Randomly seeded so a new process does not reuse a dead process's run ids. */
        private fun randomlySeededIds(): () -> Long {
            val counter = AtomicLong(Random.nextLong(0, Long.MAX_VALUE / 2))
            return { counter.incrementAndGet() }
        }
    }
}
