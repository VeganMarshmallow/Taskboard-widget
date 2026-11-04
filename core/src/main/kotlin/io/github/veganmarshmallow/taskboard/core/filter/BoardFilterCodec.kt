package io.github.veganmarshmallow.taskboard.core.filter

import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.json.JsonWriter
import io.github.veganmarshmallow.taskboard.core.json.list
import io.github.veganmarshmallow.taskboard.core.json.string
import io.github.veganmarshmallow.taskboard.core.model.Priority

/**
 * The saved [BoardFilter] as JSON (#59). Decoding never throws: unreadable text gives
 * [BoardFilter.NONE] (the board shows everything, never a mystery-empty list), a missing field is
 * empty, and values this version doesn't know (a priority added later) are skipped.
 */
object BoardFilterCodec {
    fun encode(filter: BoardFilter): String = JsonWriter.write(
        mapOf(
            "v" to VERSION,
            "repos" to filter.repos.toList(),
            "types" to filter.types.toList(),
            "priorities" to filter.priorities.map { it.name },
            "text" to filter.text,
        ),
    )

    fun decode(text: String?): BoardFilter {
        if (text.isNullOrBlank()) return BoardFilter.NONE
        return try {
            val root = JsonParser.parse(text) as? Map<*, *> ?: return BoardFilter.NONE
            BoardFilter(
                repos = strings(root, "repos").toSet(),
                types = strings(root, "types").toSet(),
                priorities = strings(root, "priorities").mapNotNull { name -> Priority.entries.firstOrNull { it.name == name } }.toSet(),
                text = root.string("text").orEmpty(),
            )
        } catch (e: Exception) {
            BoardFilter.NONE
        }
    }

    private fun strings(root: Map<*, *>, key: String): List<String> =
        root.list(key).orEmpty().filterIsInstance<String>().filter { it.isNotBlank() }

    private const val VERSION = 1L
}
