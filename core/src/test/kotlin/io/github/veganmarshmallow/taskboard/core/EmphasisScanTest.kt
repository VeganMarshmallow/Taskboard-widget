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

    @Test fun emphasisNeverReachesIntoCodeSpans() {
        assertEquals("a **b c** d", strip("*a `**b c**` d*"))
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

    @Test fun emphasisWrapsCodeSpans() {
        assertEquals("Name: does a thing", strip("**`Name`**: does a thing"))
        assertEquals("a b c", strip("**a `b` c**"))
        assertEquals("a", strip("*`a`*"))
        assertEquals("foo", strip("[`foo`](https://e.x)"))
        assertEquals("x **y**z", strip("x **`y`**z"))
        assertEquals("**", strip("`**`"))
        assertEquals("$1 \\$ x", strip("`$1 \\$` x"))
    }

    @Test fun escapedBackticksDoNotOpenCodeSpans() {
        assertEquals("`not code`", strip("\\`not code\\`"))
        assertEquals("`code", strip("\\``code`"))
        assertEquals("a\\b`", strip("`a\\`b`"))
        assertEquals("\\code", strip("\\\\`code`"))
    }

    @Test fun placeholdersInTheInputCannotBeForged() {
        assertEquals("a", strip("\uE02A a \uE02A"))
        assertEquals("x b y", strip("x \uE080\uE081b\uE02A y"))
        assertEquals("\uFFFD\uFFFD c", strip("&#xE080;&#xE081; ``c``"))
        assertEquals("a\uFFFD", strip("`a`&#57345;"))
    }

    @Test fun commonMarkCornerCases() {
        mapOf(
            "**a*" to "*a", "*a**" to "a*", "**a\nb**" to "a b", "***a** b*" to "a b", "***a* b**" to "a b",
            "*foo**bar**baz*" to "foobarbaz", "__init__" to "init", "* item *x*" to "* item x",
            "😀*a*😀" to "😀a😀", "**😀 bold**" to "😀 bold", "a😀*b*" to "a😀b", "שלום_עולם_טוב" to "שלום_עולם_טוב",
            "**שלום** עולם" to "שלום עולם", "*\\*" to "**", "_\\_a_" to "_a", "&#42;a&#42;" to "*a*",
        ).forEach { (input, expected) -> assertEquals(input, expected, strip(input)) }
        listOf("* item", "a * b * c", "~x~", "a ~ b ~ c", "~~a", "`unmatched", "a ` b").forEach { assertEquals(it, it, strip(it)) }
    }

    @Test fun emptyAndMarkerOnlyInputs() {
        listOf("", " ", "\n\n").forEach { assertEquals("", strip(it)) }
        listOf("*", "**", "***", "_", "__", "~~", "~~~~", "* * *", "_ _", "`", "``").forEach { assertEquals(it, it, strip(it)) }
        assertEquals("", strip("``  ``"))
        assertEquals("\\", strip("\\"))
    }

    @Test fun cutNeverStrandsAMarkerOrHalfASurrogate() {
        assertEquals("", MarkdownInline.strip("**abc**", 0))
        assertEquals("", MarkdownInline.strip("**abc**", -5))
        assertEquals("a", MarkdownInline.strip("**abc**", 1))
        assertEquals("abc", MarkdownInline.strip("ab**cd**", 3))
        assertEquals("ab", MarkdownInline.strip("*ab* cd", 2))
        assertEquals("c", MarkdownInline.strip("`co`de", 1))
        assertEquals("", MarkdownInline.strip("😀😀", 1))
        assertEquals("😀", MarkdownInline.strip("**😀**😀", 3))
        assertEquals("a", MarkdownInline.strip("*a*", 1))
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
            "many code spans" to "`a`**".repeat(n / 5),
            "escaped backticks" to "\\`".repeat(n / 2),
            "open brackets" to "[a](".repeat(n / 4),
            "open tags" to "<a ".repeat(n / 3),
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
