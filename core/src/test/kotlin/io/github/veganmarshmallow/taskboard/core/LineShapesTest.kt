package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.markdown.MarkdownBlock
import io.github.veganmarshmallow.taskboard.core.markdown.MarkdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Thematic breaks and table delimiter rows, now checked without regex repetition (review of #11). */
class LineShapesTest {
    private fun isRule(line: String) = MarkdownBlocks.parse("para\n\n$line") == listOf(MarkdownBlock.Paragraph("para"), MarkdownBlock.Rule)

    @Test fun rules() {
        listOf("---", "***", "___", " - - -", "   ***", "-\t-\t-", "*  *  *  ", "-----------").forEach { assertTrue(it, isRule(it)) }
        listOf("--", "-*-", "- - x", "    ---", "", "+++", "== =", "-- -a").forEach { assertTrue(it, !isRule(it)) }
    }

    @Test fun tableSeparators() {
        fun dropped(sep: String) = MarkdownBlocks.parse("| h |\n$sep") == listOf(MarkdownBlock.Paragraph("h"))
        listOf("|---|", "|---|---|", "---|---", "| :-- | --: |", "|:-:|", " | --- | --- | ", "-|-").forEach { assertTrue(it, dropped(it)) }
        // Not a separator: kept as text (or, for "---", a setext underline / rule).
        listOf("|", "|:|", "| - x |", "|-a-|").forEach { assertTrue(it, !dropped(it)) }
        assertEquals(listOf(MarkdownBlock.Paragraph("a | b")), MarkdownBlocks.parse("a | b\n--|--")) // separator dropped
    }

    @Test fun quoteMarkersAtAnyDepth() {
        assertEquals(listOf(MarkdownBlock.Paragraph("a b c d e")), MarkdownBlocks.parse("> a\n>> b\n> > c\n   >d\n>\te"))
        assertEquals(listOf(MarkdownBlock.Paragraph("x")), MarkdownBlocks.parse(">".repeat(60_000) + " x"))
        assertEquals(listOf(MarkdownBlock.Paragraph("> x")), MarkdownBlocks.parse("    > x")) // 4 spaces: not a quote marker
        assertEquals(listOf(MarkdownBlock.Paragraph("a > b")), MarkdownBlocks.parse("a > b"))
    }
}
