package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.markdown.CodeSpans
import io.github.veganmarshmallow.taskboard.core.markdown.Emphasis
import io.github.veganmarshmallow.taskboard.core.markdown.MarkdownInline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The linear emphasis and code span scanners (#25): same output as the old regexes, linear time. */
class EmphasisScanTest {
    private fun strip(s: String) = MarkdownInline.strip(s)

    @Test fun commonEmphasisIsRemoved() {
        mapOf(
            "**bold**" to "bold", "*italic*" to "italic", "_italic_" to "italic", "__under__" to "under",
            "~~strike~~" to "strike", "***both***" to "both", "___both___" to "both",
            "**_mixed_**" to "mixed", "*__nesting__*" to "nesting", "_**mixed**_" to "mixed",
            "*a **b** c*" to "a b c", "**a *b* c**" to "a b c", "x **y** z" to "x y z",
            "(**bold**)" to "(bold)", "_(paren)_" to "(paren)", "**What should be done?**" to "What should be done?",
            "a**b**c" to "abc", "**a** and **b**" to "a and b", "*a* *b* *c*" to "a b c",
            "~~a~~ ~~b~~" to "a b", "**לבנות** _דף_" to "לבנות דף", "*👩‍💻*" to "👩‍💻",
        ).forEach { (input, expected) -> assertEquals(input, expected, strip(input)) }
    }

    @Test fun markersThatAreNotEmphasisStay() {
        listOf(
            "snake_case_name", "ready_for_claude", "2 * 3 = 6", "a*b", "2*3*4", "5*60*1000", "**", "****", "*", "_",
            "*not closed", "not closed*", "** spaced**", "**spaced **", "* a *", "_ a _", "~single~", "~~~triple~~~",
            "~~ spaced~~", "file_name_v2.kt", "*.kt and *.java",
        ).forEach { assertEquals(it, it, strip(it)) }
    }

    @Test fun escapedMarkersStayLiteral() {
        assertEquals("*a* _b_ **c**", strip("\\*a\\* \\_b\\_ \\*\\*c\\*\\*"))
        assertEquals("line_here", strip("*line*\\_here"))
    }

    @Test fun emphasisDoesNotCrossCodeSpans() {
        assertEquals("*a **b c** d*", strip("*a `**b c**` d*"))
        assertEquals("*a b* c", strip("*a `b*` c"))
    }

    @Test fun codeSpansPairRunsOfEqualLength() {
        assertEquals("a`b", strip("``a`b``"))
        assertEquals("a``b", strip("`a``b`"))
        assertEquals("a and b", strip("`a` and `b`"))
        assertEquals("```", strip("```"))
        assertEquals("``a` b", strip("``a` b"))
        assertEquals(0, CodeSpans.find("no ticks").size)
        assertEquals(1, CodeSpans.find("`a` ``b").size)
    }

    @Test fun scannerAloneLeavesPlainTextUntouched() {
        assertEquals("", Emphasis.strip(""))
        assertEquals("plain text", Emphasis.strip("plain text"))
        assertEquals("a b", Emphasis.strip("*a* **b**"))
    }

    @Test fun largerCapKeepsAllTheText() {
        assertEquals("a ".repeat(3_000).trim(), MarkdownInline.strip("**a** ".repeat(3_000), Int.MAX_VALUE))
        assertEquals(MarkdownInline.MAX_VISIBLE, strip("x".repeat(5_000)).length)
    }

    @Test fun hugeHostileInputsAreLinear() {
        val n = 200_000
        val inputs = mapOf(
            "unclosed *" to "*x ".repeat(n / 3),
            "unclosed _" to "_x ".repeat(n / 3),
            "alternating *_" to "*_".repeat(n / 2),
            "alternating *_ words" to "a*_b".repeat(n / 4),
            "long runs" to "*****x".repeat(n / 6),
            "deep nesting" to "*a ".repeat(n / 6) + " a*".repeat(n / 6),
            "mixed openers" to "**_*~~x ".repeat(n / 8),
            "one backtick run" to "`".repeat(n),
            "backticks and stars" to "`*_`**x".repeat(n / 7),
            "unpaired backtick runs" to (1..600).joinToString("x") { "`".repeat(it) },
        )
        strip("**warm** _up_ `code`".repeat(1_000))
        inputs.forEach { (what, s) ->
            val start = System.nanoTime()
            val out = MarkdownInline.strip(s, Int.MAX_VALUE)
            val ms = (System.nanoTime() - start) / 1_000_000
            assertTrue("$what: empty", out.isNotEmpty())
            assertTrue("$what took $ms ms", ms < 1_000)
        }
    }
}
