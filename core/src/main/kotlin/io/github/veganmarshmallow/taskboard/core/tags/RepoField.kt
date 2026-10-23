package io.github.veganmarshmallow.taskboard.core.tags

import io.github.veganmarshmallow.taskboard.core.markdown.CodeFence
import io.github.veganmarshmallow.taskboard.core.markdown.MarkdownBlock
import io.github.veganmarshmallow.taskboard.core.markdown.MarkdownBlocks
import io.github.veganmarshmallow.taskboard.core.markdown.MarkdownInline
import io.github.veganmarshmallow.taskboard.core.markdown.MarkdownSections

/**
 * The task's project repo from its issue body: the `### Repo` (or `### Repository`) section, the
 * issue form's field, or failing that a `Repo: owner/name` line outside code. Accepts `owner/name`,
 * a github.com URL (also `git@github.com:`), Markdown links and code spans around it, and a trailing
 * `.git`. Returns only the name part, or null.
 */
object RepoField {
    private val SECTIONS = listOf("Repo", "Repository")
    private val LINE = Regex("""(?i)^[ \t>*_-]*\**repo(?:sitory)?\**[ \t]*:\**[ \t]*(.+)$""")
    private val GITHUB_URL = Regex("""(?i)^(?:(?:https?://)?(?:www\.)?github\.com/|git@github\.com:)""")

    /** GitHub owner and repo names: letters, digits, `-`, `_`, `.`; at most 100 characters. */
    private val OWNER_NAME = Regex("""^([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))/([A-Za-z0-9._-]{1,100})$""")
    private const val MAX_INPUT = 64 * 1024

    fun name(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val text = body.take(MAX_INPUT)
        val blocks = MarkdownBlocks.parse(text)
        val section = SECTIONS.firstNotNullOfOrNull { MarkdownSections.content(blocks, it) }
        // The whole first line, or just its first word ("owner/name (the new one)").
        section?.firstNotNullOfOrNull(::blockText)?.let { candidate ->
            (parse(candidate) ?: parse(candidate.trim().substringBefore(' ')))?.let { return it }
        }
        return linesOutsideCode(text).firstNotNullOfOrNull { line -> LINE.find(line)?.let { parse(it.groupValues[1]) } }
    }

    /** A `Repo:` line inside a code example is not the task's repo. */
    private fun linesOutsideCode(text: String): Sequence<String> = sequence {
        var fence: String? = null
        for (line in text.lineSequence()) {
            val open = fence
            when {
                open != null -> if (CodeFence.closes(line, open)) fence = null
                else -> {
                    fence = CodeFence.opening(line)
                    if (fence == null) yield(line)
                }
            }
        }
    }

    private fun blockText(block: MarkdownBlock): String? = when (block) {
        is MarkdownBlock.Paragraph -> block.text
        is MarkdownBlock.ListItem -> block.text
        else -> null
    }

    /** `owner/name` → `name`; null for anything else (including "_No response_" and a bare name). */
    internal fun parse(raw: String): String? {
        var s = MarkdownInline.strip(raw).trim().trim('`', '"', '\'', '<', '>', '(', ')', '.', ',', ';')
        s = GITHUB_URL.replace(s, "")
        s = s.substringBefore('#').substringBefore('?').trimEnd('/')
        val parts = s.split('/')
        if (parts.size > 2) s = parts.take(2).joinToString("/") // github.com/owner/name/issues/3
        s = s.removeSuffix(".git")
        val match = OWNER_NAME.find(s) ?: return null
        return match.groupValues[2].takeUnless { it == "." || it == ".." }
    }
}
