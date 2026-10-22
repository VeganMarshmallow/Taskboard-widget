package io.github.veganmarshmallow.taskboard.core.work

import java.time.DateTimeException
import java.time.Instant

/**
 * "A refresh is running": which run ([runId]) and since when ([startedAt]). It is persisted so a
 * new process can tell a live run from one that died with its old process, and it is never
 * trusted forever: [RefreshTracker] treats a mark older than its stale limit as dead.
 */
data class RefreshMark(val runId: Long, val startedAt: Instant)

/** Where the mark lives (SharedPreferences on Android). [save] with null clears it. */
interface RefreshMarkStore {
    fun load(): RefreshMark?
    fun save(mark: RefreshMark?)
}

/** Text form of a [RefreshMark] for storage: `"<runId>@<epochMillis>"`. Decoding never throws. */
object RefreshMarkCodec {
    fun encode(mark: RefreshMark): String = "${mark.runId}@${mark.startedAt.toEpochMilli()}"

    fun decode(text: String?): RefreshMark? {
        val parts = text?.trim()?.split('@') ?: return null
        if (parts.size != 2) return null
        val id = parts[0].toLongOrNull() ?: return null
        val millis = parts[1].toLongOrNull() ?: return null
        return try {
            RefreshMark(id, Instant.ofEpochMilli(millis))
        } catch (e: DateTimeException) {
            null
        }
    }
}
