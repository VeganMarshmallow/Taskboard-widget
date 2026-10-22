package io.github.veganmarshmallow.taskboard.core.markdown

/** A block of a Markdown document. Text is still raw inline Markdown; see [MarkdownInline]. */
sealed class MarkdownBlock {
    /** An ATX (`### Title`) or setext (`Title` over `---`) heading. */
    data class Heading(val level: Int, val text: String) : MarkdownBlock()

    /** A paragraph (also a block quote or table row), its lines joined by single spaces. */
    data class Paragraph(val text: String) : MarkdownBlock()

    /** One bullet, numbered or task-list item, with any continuation lines. */
    data class ListItem(val text: String) : MarkdownBlock()

    /** A thematic break (`---`, `***`, `___`). */
    data object Rule : MarkdownBlock()

    /** A fenced code block. Its content is deliberately dropped: code is noise in a one-line summary. */
    data object Code : MarkdownBlock()
}
