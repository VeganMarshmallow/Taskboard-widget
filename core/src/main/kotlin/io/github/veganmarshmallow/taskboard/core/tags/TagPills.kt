package io.github.veganmarshmallow.taskboard.core.tags

import io.github.veganmarshmallow.taskboard.core.board.TitleCleaner
import io.github.veganmarshmallow.taskboard.core.markdown.TextCap
import io.github.veganmarshmallow.taskboard.core.model.Labels

/**
 * The preview's tag pills, in a fixed order: the project (from the body's Repo field, coloured from
 * its name), then type, priority, Approved (`ready-for-claude`), Needs you (`needs-owner`), By Claude
 * (`created-by-claude`). Label pills use the label's own GitHub colour (grey when it is missing or
 * unreadable). `status:` labels are hidden (the status is shown on its own), and so are labels the
 * board doesn't use (e.g. GitHub's default `bug`, `enhancement`). Text is cleaned to one short line.
 */
object TagPills {
    val FALLBACK_COLOR = Rgb(0xEDEDED) // GitHub's default label grey
    const val MAX_TEXT = 40
    private const val TYPE_PREFIX = "type:"
    private const val STATUS_PREFIX = "status:"
    private val SPECIAL = mapOf(
        Labels.READY_FOR_CLAUDE to (TagPill.Kind.APPROVED to "Approved"),
        Labels.NEEDS_OWNER to (TagPill.Kind.NEEDS_YOU to "Needs you"),
        CREATED_BY_CLAUDE to (TagPill.Kind.BY_CLAUDE to "By Claude"),
        Labels.PRIORITY_HIGH to (TagPill.Kind.PRIORITY to "High priority"),
        Labels.PRIORITY_LOW to (TagPill.Kind.PRIORITY to "Low priority"),
    )

    /** [labelColors] maps normalized label names to GitHub hex colours (null when unknown). */
    fun of(labelColors: Map<String, String?>, body: String?): List<TagPill> {
        val project = RepoField.name(body)?.let { TagPill(clip(it), ProjectColor.of(it), TagPill.Kind.PROJECT) }
        val labels = labelColors.entries
            .map { (name, color) -> Labels.normalize(name) to color }
            .filter { (name, _) -> !name.startsWith(STATUS_PREFIX) }
            .distinctBy { it.first }
            .mapNotNull { (name, color) -> labelPill(name, Rgb.parse(color) ?: FALLBACK_COLOR) }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.text.lowercase() }))
        return listOfNotNull(project) + labels
    }

    private fun labelPill(name: String, color: Rgb): TagPill? {
        SPECIAL[name]?.let { (kind, text) -> return TagPill(text, color, kind) }
        val type = name.takeIf { it.startsWith(TYPE_PREFIX) }?.removePrefix(TYPE_PREFIX)?.trim()
        return type?.let { clip(capitalize(it)) }?.takeIf { it.isNotEmpty() }?.let { TagPill(it, color, TagPill.Kind.TYPE) }
    }

    private fun capitalize(s: String) = s.replaceFirstChar { it.titlecase() }

    /** One line, no bidi controls or broken UTF-16 (same cleanup as titles), capped with an ellipsis. */
    private fun clip(s: String): String = TextCap.cap(TitleCleaner.sanitize(s), MAX_TEXT)

    const val CREATED_BY_CLAUDE = "created-by-claude"
}
