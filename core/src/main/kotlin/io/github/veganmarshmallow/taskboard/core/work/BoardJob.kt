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

    /**
     * Wait for a network the system has *validated* (Android 9+ requires that for plain network
     * constraints). Urgent jobs don't: on a VPN or a phone hotspot validation can lag or flap, and
     * a tap must not wait silently for it. If the network really is down, the request fails fast
     * and the widget says so.
     */
    val needsValidatedNetwork: Boolean get() = needsNetwork && !urgent

    /**
     * For urgent jobs that are not expedited (Android 8–11, or out of expedited quota): run after
     * this long even if the network constraint is still unmet, so a tap always ends in the board
     * or an error. Null: no deadline. Android does not allow deadlines on expedited jobs.
     */
    val runAnywayAfter: Duration? get() = if (urgent) URGENT_DEADLINE else null

    abstract fun toExtras(): Map<String, String>

    /** One job id for every refresh, so scheduling a refresh replaces a pending or running one. */
    data class Refresh(val trigger: RefreshTrigger) : BoardJob() {
        override val jobId get() = REFRESH_ID
        override val urgent get() = trigger == RefreshTrigger.MANUAL
        override val needsNetwork get() = true
        override fun toExtras() = mapOf(KIND to KIND_REFRESH, TRIGGER to trigger.name)
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

    companion object {
        const val REFRESH_ID = 1
        const val SWEEP_ID = 2
        const val APPROVE_BASE_ID = 1_000_000

        /** See [runAnywayAfter]. */
        val URGENT_DEADLINE: Duration = Duration.ofSeconds(10)

        /** Keeps APPROVE_BASE_ID + issue inside Int. */
        const val MAX_ISSUE = Int.MAX_VALUE - APPROVE_BASE_ID

        private const val KIND = "kind"
        private const val TRIGGER = "trigger"
        private const val ISSUE = "issue"
        private const val KIND_REFRESH = "refresh"
        private const val KIND_APPROVE = "approve"
        private const val KIND_SWEEP = "sweep"

        /** Reads [toExtras]' output back. Null for anything unknown or malformed (e.g. a job from another app version). */
        fun fromExtras(get: (String) -> String?): BoardJob? = when (get(KIND)) {
            KIND_REFRESH -> RefreshTrigger.entries.firstOrNull { it.name == get(TRIGGER) }?.let(::Refresh)
            KIND_APPROVE -> get(ISSUE)?.toIntOrNull()?.takeIf { it in 1..MAX_ISSUE }?.let(::Approve)
            KIND_SWEEP -> Sweep
            else -> null
        }
    }
}
