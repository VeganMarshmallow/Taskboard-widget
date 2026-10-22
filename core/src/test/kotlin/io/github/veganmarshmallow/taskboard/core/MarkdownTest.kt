package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.markdown.MarkdownBlock
import io.github.veganmarshmallow.taskboard.core.markdown.MarkdownBlock.Heading
import io.github.veganmarshmallow.taskboard.core.markdown.MarkdownBlock.ListItem
import io.github.veganmarshmallow.taskboard.core.markdown.MarkdownBlock.Paragraph
import io.github.veganmarshmallow.taskboard.core.markdown.MarkdownBlocks
import io.github.veganmarshmallow.taskboard.core.markdown.MarkdownInline
import io.github.veganmarshmallow.taskboard.core.markdown.MarkdownSections
import io.github.veganmarshmallow.taskboard.core.markdown.PlainText
import io.github.veganmarshmallow.taskboard.core.markdown.TextCap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    private fun strip(s: String) = MarkdownInline.strip(s)

    // --- blocks ---

    @Test fun atxHeadingsWithLevelsAndClosingHashes() {
        assertEquals(
            listOf(Heading(1, "One"), Heading(3, "Three"), Heading(2, "Closed"), Heading(6, "")),
            MarkdownBlocks.parse("# One\n### Three\n## Closed ##\n######"),
        )
    }

    @Test fun hashWithoutSpaceIsNotAHeading() {
        assertEquals(listOf(Paragraph("#5 is an issue #hashtag")), MarkdownBlocks.parse("#5 is an issue\n#hashtag"))
        assertEquals(listOf(Paragraph("####### seven")), MarkdownBlocks.parse("####### seven"))
    }

    @Test fun setextHeadings() {
        assertEquals(listOf(Heading(1, "Title"), Heading(2, "Sub")), MarkdownBlocks.parse("Title\n=====\nSub\n---"))
    }

    @Test fun dashesRightUnderTextMakeASetextHeadingLikeOnGitHub() {
        assertEquals(listOf(Heading(2, "text")), MarkdownBlocks.parse("text\n---"))
    }

    @Test fun ruleAloneIsARuleNotAHeading() {
        assertEquals(listOf(Paragraph("a"), MarkdownBlock.Rule, Paragraph("b")), MarkdownBlocks.parse("a\n\n---\n\nb"))
        listOf("***", "* * *", "___", " - - -").forEach { assertEquals(it, listOf(MarkdownBlock.Rule), MarkdownBlocks.parse(it)) }
    }

    @Test fun listItemsAndContinuations() {
        val md = "- one\n  continued\n* two\n+ three\n1. four\n2) five\n- [ ] todo\n- [x] done\n-"
        assertEquals(
            listOf(
                ListItem("one continued"), ListItem("two"), ListItem("three"), ListItem("four"),
                ListItem("five"), ListItem("todo"), ListItem("done"),
            ),
            MarkdownBlocks.parse(md),
        )
    }

    @Test fun dashWithoutSpaceIsText() {
        assertEquals(listOf(Paragraph("-not a list")), MarkdownBlocks.parse("-not a list"))
    }

    @Test fun paragraphsJoinLinesAndSplitOnBlankLines() {
        assertEquals(listOf(Paragraph("a b"), Paragraph("c")), MarkdownBlocks.parse("a\nb\n\n\n c "))
    }

    @Test fun windowsAndOldMacLineEndings() {
        assertEquals(listOf(Heading(3, "H"), Paragraph("x y")), MarkdownBlocks.parse("### H\r\nx\ry"))
    }

    @Test fun fencedCodeIsDroppedIncludingUnclosed() {
        assertEquals(
            listOf(Paragraph("before"), MarkdownBlock.Code, Paragraph("after")),
            MarkdownBlocks.parse("before\n```kotlin\n# not a heading\nval x = 1\n```\nafter"),
        )
        assertEquals(listOf(MarkdownBlock.Code), MarkdownBlocks.parse("~~~\ncode\n### nope"))
        // A shorter closing fence does not close a longer one.
        assertEquals(listOf(MarkdownBlock.Code, Paragraph("x")), MarkdownBlocks.parse("````\n```\n````\nx"))
    }

    @Test fun inlineTripleBackticksAreNotAFence() {
        assertEquals(listOf(Paragraph("```inline``` text")), MarkdownBlocks.parse("```inline``` text"))
    }

    @Test fun blockQuotesLoseTheirMarkers() {
        assertEquals(listOf(Paragraph("quoted nested"), Heading(2, "Q")), MarkdownBlocks.parse("> quoted\n>> nested\n\n> ## Q"))
    }

    @Test fun tablesBecomeOneParagraphPerRow() {
        assertEquals(listOf(Paragraph("A, B"), Paragraph("a1, b1")), MarkdownBlocks.parse("| A | B |\n|---|:-:|\n| a1 | b1 |"))
        assertEquals("Field, Value. name, שם", PlainText.of("Intro:\n| Field | Value |\n|:--|--:|\n| name | שם |").removePrefix("Intro: "))
    }

    @Test fun htmlCommentsAndReferenceDefinitionsAreDropped() {
        assertEquals(listOf(Paragraph("x y")), MarkdownBlocks.parse("x <!-- hidden\n### H -->y\n[ref]: https://e.x"))
        assertEquals(listOf(Paragraph("x")), MarkdownBlocks.parse("x\n<!-- never closed\n### H"))
    }

    @Test fun emptyAndBlankDocuments() {
        listOf("", " ", "\n\n\t\n", "<!-- -->").forEach { assertEquals(emptyList<MarkdownBlock>(), MarkdownBlocks.parse(it)) }
    }

    @Test fun hugeDocumentParsesQuickly() {
        val md = (1..20_000).joinToString("\n") { if (it % 50 == 0) "### Section $it" else "- **item** $it [link](https://x.y/$it)" }
        val start = System.nanoTime()
        val blocks = MarkdownBlocks.parse(md)
        assertEquals(20_000, blocks.size)
        assertTrue(System.nanoTime() - start < 5_000_000_000L)
    }

    // --- inline ---

    @Test fun emphasisMarkersAreRemoved() {
        assertEquals("bold italic both strike under", strip("**bold** *italic* ***both*** ~~strike~~ __under__"))
        assertEquals("mixed nesting", strip("**_mixed_** *__nesting__*"))
    }

    @Test fun snakeCaseAndLoneStarsSurvive() {
        assertEquals("ready_for_claude and snake_case_name", strip("ready_for_claude and snake_case_name"))
        assertEquals("2 * 3 = 6, a*b", strip("2 * 3 = 6, a*b"))
        assertEquals("**", strip("**"))
        assertEquals("*not closed", strip("*not closed"))
    }

    @Test fun linksAndImagesKeepTheirText() {
        assertEquals("see the docs and logo", strip("see [the docs](https://e.x/a_(b)) and ![logo](i.png \"t\")"))
        assertEquals("ref link", strip("[ref link][1]"))
        assertEquals("https://e.x/a", strip("<https://e.x/a>"))
        assertEquals("[not a link]", strip("[not a link]"))
    }

    @Test fun codeSpansKeepContentVerbatim() {
        assertEquals("run ./gradlew **test** now", strip("run `./gradlew **test**` now"))
        assertEquals("a `b` c", strip("``a `b` c``"))
        assertEquals("unclosed `tick", strip("unclosed `tick"))
    }

    @Test fun htmlTagsAndEntities() {
        assertEquals("a b c & < > \" ' x", strip("a<br>b <b>c</b> &amp; &lt; &gt; &quot; &#39; &#x78;"))
        assertEquals("details", strip("<details><summary>details</summary></details>"))
        assertEquals("1 < 2 and 3 > 2", strip("1 < 2 and 3 > 2"))
        assertEquals("� �", strip("&#0; &#xD800;"))
        assertEquals("&unknown;", strip("&unknown;"))
    }

    @Test fun backslashEscapesAreLiteral() {
        assertEquals("*not italic* and [x](y) and \\n", strip("\\*not italic\\* and \\[x\\](y) and \\n"))
    }

    @Test fun privateUseCharactersInInputDoNotLeakAsPunctuation() {
        assertEquals("ab", strip("ab"))
    }

    @Test fun hebrewAndEmojiPassThrough() {
        assertEquals("לבנות דף נחיתה בעברית 👩‍💻", strip("**לבנות** _דף_ [נחיתה](https://x.y) `בעברית` 👩‍💻"))
    }

    @Test fun pathologicalInlineInputIsFast() {
        val s = "*".repeat(20_000) + "_".repeat(20_000) + "[".repeat(5_000) + "`".repeat(5_000)
        val start = System.nanoTime()
        strip(s)
        assertTrue(System.nanoTime() - start < 5_000_000_000L)
    }

    // --- sections ---

    @Test fun sectionRunsToNextHeadingOfSameOrHigherLevelOrARule() {
        val blocks = MarkdownBlocks.parse("## A\na\n#### deeper\nd\n### B\nb\n## C\nc\n\n---\nfooter")
        assertEquals(listOf(Paragraph("a"), Heading(4, "deeper"), Paragraph("d"), Heading(3, "B"), Paragraph("b")), MarkdownSections.content(blocks, "A"))
        assertEquals(listOf(Paragraph("b")), MarkdownSections.content(blocks, "B"))
        assertEquals(listOf(Paragraph("c")), MarkdownSections.content(blocks, "c"))
        assertNull(MarkdownSections.content(blocks, "missing"))
    }

    @Test fun sectionTitleMatchingIsForgiving() {
        val blocks = MarkdownBlocks.parse("###   **What  Should be done?** \nx")
        assertEquals(listOf(Paragraph("x")), MarkdownSections.content(blocks, "what should be done"))
        assertEquals(listOf(Paragraph("y")), MarkdownSections.content(MarkdownBlocks.parse("### Repo:\ny"), "Repo"))
    }

    @Test fun withoutSectionsDropsHeadingsAndExcludedContent() {
        val blocks = MarkdownBlocks.parse("intro\n### Repo\nowner/x\n#### sub\ns\n### Notes\nn\n\n---\nend")
        assertEquals(
            listOf(Paragraph("intro"), Paragraph("n"), MarkdownBlock.Rule, Paragraph("end")),
            MarkdownSections.withoutSections(blocks, setOf("repo")),
        )
    }

    // --- plain text ---

    @Test fun plainTextJoinsBlocksAsSentences() {
        assertEquals("Do this; and that; Then more. Done!", PlainText.of("- Do this\n- and that\n\nThen more\n\nDone!"))
        assertEquals("Title: body", PlainText.of("Title:\n\nbody"))
        assertEquals("Ends quoted.\" next", PlainText.of("Ends quoted.\"\n\nnext"))
        assertEquals("", PlainText.of("```\ncode\n```\n---"))
    }

    // --- cap ---

    @Test fun capPrefersWordBoundariesAndNeverSplitsSurrogates() {
        assertEquals("short", TextCap.cap("short", 10))
        assertEquals("exactly10!", TextCap.cap("exactly10!", 10))
        assertEquals("hello world…", TextCap.cap("hello world again", 14))
        assertEquals("hello wor…", TextCap.cap("hello world again", 10)) // boundary too early: hard cut
        assertEquals("abcdefghi…", TextCap.cap("abcdefghijklmnop", 10))
        val emoji = "a".repeat(8) + "😀😀"
        val capped = TextCap.cap(emoji, 10)
        assertTrue(capped, capped.length <= 10)
        assertTrue(!Character.isHighSurrogate(capped[capped.length - 2]))
        assertEquals("one, two…", TextCap.cap("one, two, three, four", 11))
        assertThrows(IllegalArgumentException::class.java) { TextCap.cap("x", 1) }
    }
}
