package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.preview.TaskSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSummaryTest {
    private fun summary(body: String?) = TaskSummary.of(body)

    /** The shape the board's issue form and Claude's filed issues use (see issue #11 itself). */
    private val formBody = """
        ### What should be done
        Tapping a task opens a preview inside the app instead of going straight to GitHub:
        - Title, and a very short summary of the task
        - An **Approve** button for unapproved Todo tasks

        ### Repo

        VeganMarshmallow/Taskboard-widget

        ---
        Approved by the owner in chat on 2026-10-22.
    """.trimIndent()

    @Test fun prefersWhatShouldBeDoneAndStripsMarkdown() {
        assertEquals(
            "Tapping a task opens a preview inside the app instead of going straight to GitHub: " +
                "Title, and a very short summary of the task; An Approve button for unapproved Todo tasks",
            summary(formBody),
        )
    }

    @Test fun sectionStopsAtNextHeadingAndNeverIncludesRepoOrFooter() {
        val s = summary(formBody)!!
        assertFalse(s.contains("VeganMarshmallow"))
        assertFalse(s.contains("Approved by"))
    }

    @Test fun sectionAnywhereInTheBodyAndAnyHeadingLevel() {
        assertEquals("Fix it.", summary("Intro text\n\n## Context\nlong story\n\n## What should be done\nFix it."))
        assertEquals("Do it", summary("# What should be done\nDo it"))
    }

    @Test fun emptyMainSectionFallsBackToTheRestOfTheBody() {
        assertEquals("Some context", summary("### What should be done\n\n### Context\nSome context"))
        assertEquals("Context here", summary("### What should be done\n_No response_\n\n### Context\nContext here"))
        assertEquals("Real text", summary("### Notes\n\n_No response_\n\nReal text"))
    }

    @Test fun noSectionUsesStartOfBodyWithoutHeadingsRepoOrFooter() {
        assertEquals(
            "Plain description here. Second paragraph",
            summary("## Overview\nPlain description here.\n\nSecond paragraph\n\n### Repo\nowner/name\n\n---\nfooter"),
        )
    }

    @Test fun leadingRuleIsSkippedNotTreatedAsTheEnd() {
        assertEquals("Body after rule", summary("---\nBody after rule"))
    }

    @Test fun emptyOrMeaninglessBodiesGiveNull() {
        listOf(
            null, "", "   ", "\n\n", "### What should be done", "### What should be done\n\n### Repo\nowner/x",
            "```\nonly code\n```", "<!-- comment only -->", "---", "### Repo\nVeganMarshmallow/x\n\n---\nApproved", "_No response_",
            "\u202E\u200B", // a bidi control and a zero-width space only
        ).forEach { assertNull("$it", summary(it)) }
    }

    @Test fun capsAtAbout200CharactersOnAWordBoundary() {
        val words = (1..100).joinToString(" ") { "word$it" }
        val s = summary("### What should be done\n$words")!!
        assertTrue(s.length <= TaskSummary.MAX_LENGTH)
        assertTrue(s.length > 150)
        assertTrue(s.endsWith("…"))
        assertTrue(Regex("""word\d+…$""").containsMatchIn(s)) // no half word
    }

    @Test fun exactly200IsNotCut() {
        val text = "a".repeat(200)
        assertEquals(text, summary(text))
        assertEquals(TaskSummary.MAX_LENGTH, summary("a".repeat(201))!!.length)
    }

    @Test fun hugeBodyIsBoundedAndFast() {
        val huge = "### What should be done\n" + "Lorem ipsum **dolor** sit [amet](https://e.x). ".repeat(50_000)
        val start = System.nanoTime()
        val s = summary(huge)!!
        assertTrue(System.nanoTime() - start < 5_000_000_000L)
        assertTrue(s.startsWith("Lorem ipsum dolor sit amet."))
        assertTrue(s.length <= 200)
    }

    @Test fun hugeSingleLineWithoutSpaces() {
        assertEquals(200, summary("x".repeat(1_000_000))!!.length)
    }

    @Test fun hebrewBody() {
        val body = """
            ### מה צריך לעשות
            לא רלוונטי
            ### What should be done
            - לבנות **דף נחיתה** בעברית
            - תמיכה מלאה ב־[RTL](https://example.com)

            ### Repo
            VeganMarshmallow/site
        """.trimIndent()
        assertEquals("לבנות דף נחיתה בעברית; תמיכה מלאה ב־RTL", summary(body))
    }

    @Test fun hebrewOnlyBodyWithoutSections() {
        assertEquals("שלום עולם. זו משימה", summary("שלום עולם\n\nזו משימה"))
    }

    @Test fun longHebrewIsCappedWithoutBreakingCharacters() {
        val s = summary("מילה ".repeat(200))!!
        assertTrue(s.length <= 200)
        assertTrue(s.endsWith("מילה…"))
    }

    @Test fun markdownHeavyBody() {
        val body = """
            ### What should be done
            > **Note:** see `docs/README.md` and ![img](x.png)
            1. [ ] Add `--flag` to the <kbd>CLI</kbd>
            2. [x] ~~Remove~~ *old* code &amp; tests
            | Col | Val |
            |-----|-----|
            | a   | 1   |
            ```bash
            rm -rf /
            ```
            Final *line*\_here
        """.trimIndent()
        assertEquals(
            // The quote is a paragraph (". "), list items join with "; ", each table row is its own sentence.
            "Note: see docs/README.md and img. Add --flag to the CLI; Remove old code & tests; Col, Val. a, 1. Final line_here",
            summary(body),
        )
    }

    /** The same input the on-device test runs (PreviewViewsTest), so JVM and Android regex engines must agree. */
    @Test fun sameAsOnDevice() {
        assertEquals(
            "Note: see docs/README.md and img. Add --flag to the CLI; Remove old code & tests; Final line_here",
            summary("### What should be done\n> **Note:** see `docs/README.md` and ![img](x.png)\n1. [ ] Add `--flag` to the <kbd>CLI</kbd>\n2. [x] ~~Remove~~ *old* code &amp; tests\n\nFinal *line*\\_here"),
        )
        assertEquals("ready_for_claude a*b", summary("ready_for_claude a*b"))
    }

    @Test fun emojiAndZwjSequencesSurviveAndAreNotCutInHalf() {
        val s = summary("### What should be done\n" + "👩‍👩‍👧 ".repeat(100))!!
        assertTrue(s.length <= 200)
        assertFalse(s.dropLast(1).endsWith("‍"))
        assertFalse(Character.isHighSurrogate(s[s.length - 2]))
    }

    @Test fun bidiControlsAndBrokenSurrogatesAreCleaned() {
        assertEquals("abc �", summary("a\u202Eb\u2066c \uD800"))
    }

    @Test fun whitespaceIsCollapsed() {
        assertEquals("a b c", summary("a\t\t b  c"))
    }

    @Test fun malformedMarkdownNeverThrows() {
        listOf("[", "](", "**", "``", "<", "&#", "### ", "|", "| |", "\\", "- ", "1.", ">", "<!--", "~~~", "*_*_*_").forEach {
            summary(it) // must not throw
        }
    }

    @Test fun customMaxLength() {
        assertEquals("one two…", TaskSummary.of("one two three four", maxLength = 10))
    }
}
