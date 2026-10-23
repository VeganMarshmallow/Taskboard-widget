package io.github.veganmarshmallow.taskboard.core.task

import io.github.veganmarshmallow.taskboard.core.model.Repo

/**
 * Project repos already referenced on the board, for the form's suggestions. A task names its
 * repo in a `### Repo` section (the issue form) or a `Repo: owner/name` line (issues Claude
 * files). Anything that isn't a valid `owner/name`, such as `_No response_`, is ignored.
 */
object RepoSuggestions {
    const val MAX = 20

    private val REPO_SECTION = Regex("^ {0,3}###\\s+Repo\\s*$", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val REPO_LINE = Regex("^\\s*[*_]*Repo[*_]*\\s*:[*_]*\\s*(\\S+)", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    /**
     * Distinct repos from [bodies] (newest issue first), most-referenced first, ties by recency.
     * Repos differing only in case are one repo; the newest spelling is kept.
     */
    fun fromBodies(bodies: List<String?>): List<String> {
        val counts = LinkedHashMap<String, Pair<String, Int>>() // lowercase -> (spelling, count)
        for (body in bodies) {
            val repo = repoIn(body ?: continue) ?: continue
            val key = repo.fullName.lowercase()
            val seen = counts[key]
            counts[key] = (seen?.first ?: repo.fullName) to ((seen?.second ?: 0) + 1)
        }
        return counts.values.withIndex()
            .sortedWith(compareBy({ -it.value.second }, { it.index }))
            .map { it.value.first }
            .take(MAX)
    }

    /** The repo one issue body names, or null. */
    fun repoIn(body: String): Repo? {
        val text = body.replace("\r\n", "\n")
        REPO_SECTION.find(text)?.let { heading ->
            val value = text.substring(heading.range.last + 1).lineSequence()
                .map(String::trim).dropWhile(String::isEmpty).firstOrNull()
            if (value != null && !value.startsWith("#")) RepoInput.parseStrict(stripLink(value))?.let { return it }
        }
        return REPO_LINE.find(text)?.groupValues?.get(1)?.let { RepoInput.parseStrict(stripLink(it.trimEnd('*', '_', '.', ','))) }
    }

    /** Suggestions matching [query]: owner/name or name containing it, case-insensitively; all when blank. */
    fun matching(all: List<String>, query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return all
        return all.filter { it.contains(q, ignoreCase = true) }
    }

    private fun stripLink(value: String): String = value.removePrefix("`").removeSuffix("`")
}
