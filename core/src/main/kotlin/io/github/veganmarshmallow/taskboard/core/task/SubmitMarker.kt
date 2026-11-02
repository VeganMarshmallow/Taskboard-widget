package io.github.veganmarshmallow.taskboard.core.task

import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.json.JsonWriter
import java.time.Instant

/**
 * "A create of [title] was sent at [startedAt]" (device wall clock). Saved before the POST and
 * cleared when it completes, so one is only ever left behind by a process that died mid-submit.
 */
data class SubmitMarker(val title: String, val startedAt: Instant)

/** JSON text form of a [SubmitMarker] for storage. Decoding never throws; anything malformed decodes to null. */
object SubmitMarkerCodec {
    fun encode(marker: SubmitMarker): String =
        JsonWriter.write(mapOf("title" to marker.title, "startedAt" to marker.startedAt.toEpochMilli()))

    fun decode(text: String?): SubmitMarker? {
        val root = StoredJson.objectOrNull(text) ?: return null
        val title = (root["title"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val millis = root["startedAt"] as? Long ?: return null
        return SubmitMarker(title, Instant.ofEpochMilli(millis))
    }
}

/** Parses stored JSON objects without throwing. Shared by the New task form's codecs. */
internal object StoredJson {
    fun objectOrNull(text: String?): Map<*, *>? {
        if (text.isNullOrBlank()) return null
        return try {
            JsonParser.parse(text) as? Map<*, *>
        } catch (e: Exception) {
            null
        }
    }
}
