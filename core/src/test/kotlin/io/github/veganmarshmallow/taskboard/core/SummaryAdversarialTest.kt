package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.markdown.MarkdownBlock.Paragraph
import io.github.veganmarshmallow.taskboard.core.markdown.MarkdownBlocks
import io.github.veganmarshmallow.taskboard.core.markdown.MarkdownInline
import io.github.veganmarshmallow.taskboard.core.preview.ClaudeComments
import io.github.veganmarshmallow.taskboard.core.preview.IssueComment
import io.github.veganmarshmallow.taskboard.core.preview.TaskSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Summary extraction on hostile or unusual Markdown (review of #11). */
class SummaryAdversarialTest {
    private val fence = "```"
    private fun summary(body: String) = TaskSummary.of(body)

    // --- the main section heading inside code or comments is not a heading ---

    @Test fun headingInsideAFenceIsNotTheSection() {
        val body = "Intro text\n\n${fence}md\n### What should be done\nfake\n$fence\n\n### What should be done\nReal work"
        assertEquals("Real work", summary(body))
    }

    @Test fun headingOnlyInsideAFenceFallsBackToTheBody() {
        assertEquals("Intro text. after", summary("Intro text\n\n$fence\n### What should be done\nfake\n$fence\nafter"))
        assertEquals("Real", summary("~~~\n### What should be done\nfake\n~~~\n### What should be done\nReal"))
        assertNull(summary("$fence\n### What should be done\n$fence"))
    }

    @Test fun headingInsideAnHtmlCommentIsNotTheSection() {
        assertEquals("Real intro", summary("<!--\n### What should be done\nfake\n-->\nReal intro"))
    }

    @Test fun fenceInsideAListItemDoesNotLeakItsHeading() {
        val body = "### What should be done\n- step one\n  $fence\n  ### What should be done\n  code\n  $fence\n- step two"
        assertEquals("step one; step two", summary(body))
    }

    // --- `<!--` in code is text, not the start of a comment (it used to swallow the rest of the body) ---

    @Test fun commentOpenerInACodeSpanKeepsTheRestOfTheBody() {
        assertEquals("Strip <!-- markers from bodies, then render.", summary("### What should be done\nStrip `<!--` markers from bodies, then render.\n\n### Repo\nx/y"))
        assertEquals("Real", summary("Use `<!--` here\n\n### What should be done\nReal"))
    }

    @Test fun commentOpenerInAFenceKeepsTheRestOfTheBody() {
        assertEquals("Real text after the fence", summary("### What should be done\n${fence}html\n<!-- open\n$fence\nReal text after the fence\n\n### Repo\nx/y"))
    }

    @Test fun commentsOutsideCodeAreStillDropped() {
        assertEquals("Do this", summary("### What should be done\nDo <!-- hidden --> this"))
        assertEquals("Real", summary("<!-- template\nhint\n-->\n### What should be done\nReal"))
        assertEquals("Before after", summary("Before <!-- a\nb\nc --> after"))
        assertEquals("Kept", summary("Kept\n\n<!-- never closed\n### What should be done\nhidden"))
        assertEquals("a `<!--` b", MarkdownInline.strip("a ``` `<!--` ``` b"))
    }

    @Test fun commentBlockDoesNotSplitTheParagraphAroundIt() {
        assertEquals(listOf(Paragraph("one two")), MarkdownBlocks.parse("one <!-- x\ny\nz --> two"))
        assertEquals(listOf(Paragraph("tick `")), MarkdownBlocks.parse("tick ` <!-- x")) // a lone backtick is not a code span
    }

    @Test fun claudeLineWithACommentOpenerInCodeIsNotTruncated() {
        val line = ClaudeComments.latestLine(listOf(IssueComment(1, "🤖 Started\n$fence\n<!-- x\n$fence\nPlan: a", null)))
        assertEquals("🤖 Started. Plan: a", line)
    }

    // --- lists, HTML, tables, links ---

    @Test fun nestedListsReadAsOneSentence() {
        assertEquals(
            "Parent; Child one; Grandchild; Second; numbered",
            summary("### What should be done\n- Parent\n  - Child one\n    - Grandchild\n- Second\n  1. numbered\n"),
        )
    }

    @Test fun htmlTagsAreRemovedButTheirTextStays() {
        assertEquals(
            "More. Hidden text bold end",
            summary("### What should be done\n<details><summary>More</summary>\n\nHidden text\n</details>\n<b>bold</b> <img src=\"x.png\"> end"),
        )
        assertEquals("alert(1) <b> א ok", summary("### What should be done\n<script>alert(1)</script> &lt;b&gt; &#x5D0; ok"))
    }

    @Test fun tableRowsBecomeSentences() {
        assertEquals("Field, Value. name, שם. age, 3", summary("### What should be done\n| Field | Value |\n|:--|--:|\n| name | שם |\n| age | 3 |"))
    }

    @Test fun hebrewLinkTextIsKeptWithoutUrls() {
        assertEquals(
            "ראו קישור לעיצוב ו-עוד כאן",
            summary("### What should be done\nראו [קישור לעיצוב](https://figma.com/x?a=(1)) ו-[עוד](<https://a.b/c d>) כאן"),
        )
        assertEquals("‏קישור‏ ו-שם", summary("[‏קישור‏](https://x.y \"כותרת\") ו-![שם](i.png)"))
    }

    @Test fun linkTextWithBalancedBracketsDropsTheUrl() {
        assertEquals("a [b] c and ref", summary("### What should be done\n[a [b] c](https://x.y) and [ref][1]\n\n[1]: https://x"))
        assertEquals("see [x] y", MarkdownInline.strip("see [[x] y](https://e.x)"))
    }

    @Test fun unbalancedBracketsAndHugeBracketRunsStayFast() {
        val start = System.nanoTime()
        val evil = "[".repeat(20_000) + "a" + "]".repeat(5_000) + "(" + "x".repeat(10_000)
        assertTrue(MarkdownInline.strip(evil).isNotEmpty())
        assertTrue(MarkdownInline.strip("<!--".repeat(20_000)).isNotEmpty())
        assertTrue(MarkdownBlocks.parse("`<!--`\n".repeat(20_000) + "x").isNotEmpty())
        assertTrue(System.nanoTime() - start < 5_000_000_000L)
    }

    // --- long lines: these used to overflow the stack (a crash) or take close to a minute (an ANR) ---

    private fun fast(what: String, block: () -> Unit) {
        val start = System.nanoTime()
        block()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("$what took $ms ms", ms < 3_000)
    }

    @Test fun linkWithAVeryLongUrlDoesNotOverflowTheStack() {
        fast("summary") {
            assertEquals("See design", summary("### What should be done\nSee [design](https://x.y/?q=" + "a".repeat(60_000) + ")"))
        }
        fast("unclosed target") { assertTrue(summary("[x](" + "y".repeat(60_000))!!.isNotEmpty()) }
        fast("claude line") {
            val body = "🤖 Screenshot [x](data:image/png;base64," + "A".repeat(50_000) + ") attached" // under the 64 KB input cap
            assertEquals("🤖 Screenshot x attached", ClaudeComments.latestLine(listOf(IssueComment(1, body, null))))
        }
        // More than 8 nested groups is past the bounded pattern: left as text, but no crash and no hang.
        fast("nested parens") { assertTrue(MarkdownInline.strip("[a](" + "(x)".repeat(20_000) + ")").startsWith("[a](")) }
        assertEquals("a", MarkdownInline.strip("[a](" + "(x)".repeat(8) + ")"))
    }

    @Test fun longRuleAndTableLinesDoNotOverflowTheStack() {
        fast("rule") { assertEquals("x", summary("x\n\n" + "- ".repeat(50_000) + "\nfooter")) }
        fast("star rule") { assertEquals("x", summary("x\n\n" + "*".repeat(50_000) + "\nfooter")) }
        fast("table separator") { assertEquals("a, b. 1, 2", summary("| a | b |\n" + "|-".repeat(20_000) + "|\n| 1 | 2 |")) }
        fast("table row") { assertTrue(summary("|" + "a|".repeat(50_000))!!.startsWith("a, a, a")) }
    }

    @Test fun hugeHeadingsAreMatchedQuickly() {
        fast("heading") { assertEquals("Real", summary("### *a" + " *b".repeat(15_000) + "\nx\n### What should be done\nReal")) }
    }

    @Test fun thousandsOfUnclosedEmphasisMarkersStayFast() {
        fast("stars") { assertTrue(summary("### What should be done\n*a" + " *b".repeat(30_000))!!.length <= TaskSummary.MAX_LENGTH) }
        fast("underscores") { assertTrue(summary("_a" + " _b".repeat(30_000))!!.length <= TaskSummary.MAX_LENGTH) }
        fast("claude stars") { assertTrue(ClaudeComments.latestLine(listOf(IssueComment(1, "✅ *a" + " *b".repeat(30_000), null)))!!.startsWith("✅ *a *b")) }
        fast("many blocks") { assertTrue(summary("*a *b\n\n".repeat(20_000))!!.length <= TaskSummary.MAX_LENGTH) }
    }
}
