package io.github.veganmarshmallow.taskboard.core.work

/**
 * A point on the device's monotonic clock: which boot it is, and the time since that boot
 * (SystemClock.elapsedRealtime on Android, which counts deep sleep too). Unlike wall time it never
 * jumps when the user, the network (NITZ) or a VPN changes the clock, so it is what decides
 * whether a refresh is still running. [boot] is [UNKNOWN_BOOT] when the device does not say.
 */
data class UptimeInstant(val boot: Int, val elapsedMs: Long) {
    companion object {
        const val UNKNOWN_BOOT = -1
    }
}

/** Reads the device's [UptimeInstant] now. */
fun interface Uptime {
    fun now(): UptimeInstant
}
