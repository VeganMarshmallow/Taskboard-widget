package io.github.veganmarshmallow.taskboard.core.merge

import io.github.veganmarshmallow.taskboard.core.model.Repo

/**
 * Finds pull requests mentioned in a task's comments. Two shapes count:
 *
 * - a PR URL, `https://github.com/<owner>/<repo>/pull/<n>` (with or without `www.`, trailing
 *   `/files`, `#...` or `?...`);
 * - `<owner>/<repo> PR #<n>`, or `<repo> PR #<n>` when `<repo>` is one of [knownRepos] (the
 *   task's Repo field). A bare word before "PR #n" is otherwise ignored, so "merge the PR #3"
 *   never turns into a repo called "the".
 *
 * Links are returned newest comment first and, within a comment, in reading order, without
 * duplicates and capped at [MAX_LINKS]. Malformed numbers (0, too long) are skipped.
 */
object PrLinks {
    const val MAX_LINKS = 5
    private const val MAX_INPUT = 64 * 1024
    private const val OWNER = GitHubNames.OWNER
    private const val NAME = GitHubNames.NAME
    private val URL = Regex("""(?i)\bhttps?://(?:www\.)?github\.com/($OWNER)/($NAME)/pull/(\d{1,9})(?![\d])""")
    private val SHORT = Regex("""(?<![\w./-])(?:($OWNER)/)?($NAME)\s+PR\s*#(\d{1,9})(?![\d])""", RegexOption.IGNORE_CASE)

    /** [comments] are oldest first, as GitHub lists them. */
    fun fromComments(comments: List<String>, knownRepos: Collection<Repo>): List<PullRef> =
        comments.asReversed().asSequence()
            .flatMap { parse(it, knownRepos) }
            .distinct()
            .take(MAX_LINKS)
            .toList()

    fun parse(text: String, knownRepos: Collection<Repo>): List<PullRef> {
        val input = text.take(MAX_INPUT)
        val found = URL.findAll(input).mapNotNull { m -> ref(m.range.first, m.groupValues[1], m.groupValues[2], m.groupValues[3]) } +
            SHORT.findAll(input).mapNotNull { m -> short(m, knownRepos) }
        return found.sortedBy { it.first }.map { it.second }.distinct().toList()
    }

    private fun short(m: MatchResult, knownRepos: Collection<Repo>): Pair<Int, PullRef>? {
        val owner = m.groupValues[1]
        val name = m.groupValues[2]
        if (owner.isNotEmpty()) return ref(m.range.first, owner, name, m.groupValues[3])
        val known = knownRepos.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
        return ref(m.range.first, known.owner, known.name, m.groupValues[3])
    }

    private fun ref(at: Int, owner: String, rawName: String, number: String): Pair<Int, PullRef>? {
        val name = rawName.removeSuffix(".git").trimEnd('.')
        if (name.isEmpty() || name == "." || name == "..") return null
        val n = number.toIntOrNull()?.takeIf { it > 0 } ?: return null
        return at to PullRef(Repo(owner, name), n)
    }
}
