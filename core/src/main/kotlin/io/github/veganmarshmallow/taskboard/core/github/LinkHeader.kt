package io.github.veganmarshmallow.taskboard.core.github

/** Parses RFC 8288 `Link` headers as GitHub sends them for pagination. */
object LinkHeader {
    private val entry = Regex("""^\s*<([^>]*)>\s*(.*)$""")
    private val relParam = Regex("""(?i)(?:^|;)\s*rel\s*=\s*"?([^";]*)"?""")

    /** URL of the `rel="next"` page, or null when there is none or the header is malformed. */
    fun nextUrl(header: String?): String? = relations(header)["next"]

    /** All `rel -> url` pairs found. Malformed entries are skipped. */
    fun relations(header: String?): Map<String, String> {
        if (header.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (part in splitEntries(header)) {
            val match = entry.find(part) ?: continue
            val url = match.groupValues[1].trim()
            if (url.isEmpty()) continue
            val rels = relParam.find(match.groupValues[2])?.groupValues?.get(1) ?: continue
            rels.split(' ').filter { it.isNotBlank() }.forEach { rel -> result.putIfAbsent(rel.lowercase(), url) }
        }
        return result
    }

    /** Splits on commas that are outside `<...>`, since URLs may contain commas. */
    private fun splitEntries(header: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        val current = StringBuilder()
        for (c in header) {
            when {
                c == '<' -> { depth++; current.append(c) }
                c == '>' -> { if (depth > 0) depth--; current.append(c) }
                c == ',' && depth == 0 -> { parts.add(current.toString()); current.clear() }
                else -> current.append(c)
            }
        }
        parts.add(current.toString())
        return parts
    }
}
