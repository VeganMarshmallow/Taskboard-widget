package io.github.veganmarshmallow.taskboard.core.pace

import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.json.JsonWriter
import io.github.veganmarshmallow.taskboard.core.json.string

/**
 * The text of `.board/settings.json` (#83): reads the pace out of it, and writes a pace into it
 * keeping every other key (Claude's runs or the owner may add more settings later). Never throws.
 */
object BoardSettings {
    const val PATH = ".board/settings.json"
    const val PACE_KEY = "pace"

    /** The pace [text] stores; [WorkPace.DEFAULT] for null (no file), invalid JSON, a non-object, or a missing/unknown/non-string value. */
    fun pace(text: String?): WorkPace = WorkPace.of(objectOf(text)?.string(PACE_KEY))

    /** Whether [text] already stores exactly [pace]'s own value (then saving it again would change nothing). */
    fun stores(text: String?, pace: WorkPace): Boolean = objectOf(text)?.string(PACE_KEY) == pace.value

    /**
     * [text] with `"pace"` set to [pace], other keys kept in their order. A missing, invalid or
     * non-object file is replaced by a fresh object (there is nothing in it to keep).
     */
    fun withPace(text: String?, pace: WorkPace): String {
        val fields = LinkedHashMap<String, Any?>()
        objectOf(text)?.forEach { (k, v) -> fields[k.toString()] = v }
        fields[PACE_KEY] = pace.value
        return JsonWriter.write(fields) + "\n"
    }

    /**
     * Whether [text] has content [withPace] can't keep (not blank, but not a JSON object): saving
     * replaces it. The old text stays in the file's git history; the commit message says so.
     */
    fun replacesUnreadable(text: String?): Boolean = !text.isNullOrBlank() && objectOf(text) == null

    private fun objectOf(text: String?): Map<*, *>? {
        if (text.isNullOrBlank()) return null
        return try {
            JsonParser.parse(text.removePrefix("﻿")) as? Map<*, *>
        } catch (e: Exception) {
            null
        }
    }
}
