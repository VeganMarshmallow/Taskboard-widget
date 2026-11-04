package io.github.veganmarshmallow.taskboard.core.work

import java.time.Duration

/**
 * Background work the widget schedules, with its scheduling rules. The Android layer maps this
 * onto JobScheduler; keeping the rules here keeps them testable. Every job has at least one
 * constraint (network or a delay), because JobScheduler rejects jobs without any.
 */
sealed class BoardJob {
    abstract val jobId: Int

    /** Run as soon as possible (expedited on Android 12+): someone is waiting. */
    abstract val urgent: Boolean

    /** Wait for a network connection before starting. */
    abstract val needsNetwork: Boolean

    /** Earliest start, counted from scheduling. */
    open val minLatency: Duration get() = Duration.ZERO

    /*
     * No job waits for a *validated* network (what a plain network constraint means from Android 9).
     * On a VPN or a phone hotspot validation can lag, flap or never happen, and a refresh waiting for
     * it left the widget on "No network" after the network was back (#29). Any network with internet
     * will do, a VPN included; if it does not pass traffic, the request fails fast and is retried.
     */

    /** Keep the job across reboots, so a pending retry still runs after the phone restarts. */
    open val persisted: Boolean get() = false

    /**
     * For urgent jobs that are not expedited (Android 8–11, or out of expedited quota): run after
     * this long even if the network constraint is still unmet, so a tap always ends in the board
     * or an error. Null: no deadline. Android does not allow deadlines on expedited jobs.
     */
    val runAnywayAfter: Duration? get() = if (urgent) URGENT_DEADLINE else null

    abstract fun toExtras(): Map<String, String>

    /**
     * One job id for every refresh, so scheduling a refresh replaces a pending or running one.
     * [delay]: wait at least this long first (a retry's backoff); it also waits for a network.
     * Background refreshes are persisted; a tap's is not (it runs at once or not at all).
     */
    data class Refresh(val trigger: RefreshTrigger, val delay: Duration = Duration.ZERO) : BoardJob() {
        init {
            require(!delay.isNegative) { "delay must not be negative" }
        }

        override val jobId get() = REFRESH_ID
        override val urgent get() = trigger == RefreshTrigger.MANUAL
        override val needsNetwork get() = true
        override val minLatency: Duration get() = delay
        override val persisted get() = !urgent
        override fun toExtras() = buildMap {
            put(KIND, KIND_REFRESH)
            put(TRIGGER, trigger.name)
            if (!delay.isZero) put(DELAY_MS, delay.toMillis().toString())
        }
    }

    /** One job per issue, so approving two tasks quickly runs both. */
    data class Approve(val issue: Int) : BoardJob() {
        init {
            require(issue in 1..MAX_ISSUE) { "issue out of range" }
        }

        override val jobId get() = APPROVE_BASE_ID + issue
        override val urgent get() = true
        override val needsNetwork get() = true
        override fun toExtras() = mapOf(KIND to KIND_APPROVE, ISSUE to issue.toString())
    }

    /** Runs after a refresh's stale limit and turns a dead run's "updating…" into an error. Needs no network. */
    data object Sweep : BoardJob() {
        override val jobId get() = SWEEP_ID
        override val urgent get() = false
        override val needsNetwork get() = false
        override val minLatency: Duration get() = RefreshTracker.STALE_AFTER.plusSeconds(5)
        override fun toExtras() = mapOf(KIND to KIND_SWEEP)
    }

    /**
     * Takes a saved Merge all run (#57) as far as it can, then schedules itself again after [delay]
     * (waiting for checks) until the run ends. One job id, so a new one replaces a pending one.
     * Persisted, so a run waiting for checks survives a reboot.
     */
    data class MergeAll(val delay: Duration = Duration.ZERO) : BoardJob() {
        init {
            require(!delay.isNegative) { "delay must not be negative" }
        }

        override val jobId get() = MERGE_ALL_ID
        override val urgent get() = false
        override val needsNetwork get() = true
        override val minLatency: Duration get() = delay
        override val persisted get() = true
        override fun toExtras() = buildMap {
            put(KIND, KIND_MERGE_ALL)
            if (!delay.isZero) put(DELAY_MS, delay.toMillis().toString())
        }
    }

    companion object {
        const val REFRESH_ID = 1
        const val SWEEP_ID = 2
        const val MERGE_ALL_ID = 3
        const val APPROVE_BASE_ID = 1_000_000

        /** See [runAnywayAfter]. */
        val URGENT_DEADLINE: Duration = Duration.ofSeconds(10)

        /** Keeps APPROVE_BASE_ID + issue inside Int. */
        const val MAX_ISSUE = Int.MAX_VALUE - APPROVE_BASE_ID

        private const val KIND = "kind"
        private const val TRIGGER = "trigger"
        private const val ISSUE = "issue"
        private const val DELAY_MS = "delayMs"
        private const val KIND_REFRESH = "refresh"
        private const val KIND_APPROVE = "approve"
        private const val KIND_SWEEP = "sweep"
        private const val KIND_MERGE_ALL = "merge_all"

        /** Reads [toExtras]' output back. Null for anything unknown or malformed (e.g. a job from another app version). */
        fun fromExtras(get: (String) -> String?): BoardJob? = when (get(KIND)) {
            KIND_REFRESH -> refreshFromExtras(get)
            KIND_APPROVE -> get(ISSUE)?.toIntOrNull()?.takeIf { it in 1..MAX_ISSUE }?.let(::Approve)
            KIND_SWEEP -> Sweep
            KIND_MERGE_ALL -> delayFromExtras(get)?.let(::MergeAll)
            else -> null
        }

        private fun refreshFromExtras(get: (String) -> String?): Refresh? {
            val trigger = RefreshTrigger.entries.firstOrNull { it.name == get(TRIGGER) } ?: return null
            return delayFromExtras(get)?.let { Refresh(trigger, it) }
        }

        /** The optional delay; null when it is there but malformed. */
        private fun delayFromExtras(get: (String) -> String?): Duration? {
            val delayMs = get(DELAY_MS)?.let { it.toLongOrNull()?.takeIf { ms -> ms >= 0 } ?: return null } ?: 0L
            return Duration.ofMillis(delayMs)
        }
    }
}
