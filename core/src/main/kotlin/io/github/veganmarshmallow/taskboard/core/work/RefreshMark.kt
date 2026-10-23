package io.github.veganmarshmallow.taskboard.core.work

/**
 * "A refresh is running": which run ([runId]) and since when ([startedAt], on the monotonic
 * [Uptime] clock, never wall time). It is persisted so a new process can tell a live run from one
 * that died with its old process (or with the whole device), and it is never trusted forever:
 * [RefreshTracker] treats a mark older than its stale limit, or from another boot, as dead.
 */
data class RefreshMark(val runId: Long, val startedAt: UptimeInstant)

/** Where the mark lives (SharedPreferences on Android). [save] with null clears it. */
interface RefreshMarkStore {
    fun load(): RefreshMark?
    fun save(mark: RefreshMark?)
}

/**
 * Text form of a [RefreshMark] for storage: `"<runId>@<boot>@<elapsedMs>"`. Decoding never throws;
 * anything else, including the earlier wall-clock form `"<runId>@<epochMillis>"`, decodes to null.
 */
object RefreshMarkCodec {
    fun encode(mark: RefreshMark): String = "${mark.runId}@${mark.startedAt.boot}@${mark.startedAt.elapsedMs}"

    fun decode(text: String?): RefreshMark? {
        val parts = text?.trim()?.split('@') ?: return null
        if (parts.size != 3) return null
        val id = parts[0].toLongOrNull() ?: return null
        val boot = parts[1].toIntOrNull() ?: return null
        val elapsed = parts[2].toLongOrNull() ?: return null
        return RefreshMark(id, UptimeInstant(boot, elapsed))
    }
}
