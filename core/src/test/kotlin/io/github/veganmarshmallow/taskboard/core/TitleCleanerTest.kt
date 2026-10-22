package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.TitleCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleCleanerTest {
    @Test fun keepsNormalTitles() = assertEquals("Add login page", TitleCleaner.clean("Add login page"))

    @Test fun collapsesWhitespaceAndControlCharacters() {
        assertEquals("a b c d", TitleCleaner.clean(" a\r\n b\t\tc\u0000d  "))
    }

    @Test fun blankBecomesUntitled() {
        listOf("", "   ", "\n\t", "\u0007").forEach { assertEquals(TitleCleaner.UNTITLED, TitleCleaner.clean(it)) }
    }

    @Test fun exactLimitIsNotTruncated() {
        val s = "x".repeat(200)
        assertEquals(s, TitleCleaner.clean(s))
    }

    @Test fun overLimitIsTruncatedWithEllipsis() {
        val out = TitleCleaner.clean("y".repeat(201))
        assertEquals(200, out.length)
        assertTrue(out.endsWith("…"))
        assertEquals(200, TitleCleaner.clean("z".repeat(10_000)).length)
    }

    @Test fun neverSplitsSurrogatePairs() {
        for (max in 1..12) {
            val out = TitleCleaner.clean("a🚀🚀🚀🚀🚀🚀🚀🚀", max)
            assertTrue(out.length <= max)
            out.forEachIndexed { i, c ->
                if (Character.isHighSurrogate(c)) assertTrue("dangling high surrogate at $i in '$out'", i + 1 < out.length && Character.isLowSurrogate(out[i + 1]))
                if (Character.isLowSurrogate(c)) assertTrue(i > 0 && Character.isHighSurrogate(out[i - 1]))
            }
        }
    }

    @Test fun keepsUnicodeAndRtl() {
        assertEquals("📋 לוח משימות ✅", TitleCleaner.clean("📋 לוח משימות ✅"))
    }

    @Test fun trailingSpaceBeforeEllipsisIsTrimmed() {
        assertFalse(TitleCleaner.clean("aaaa bbbb", 6).contains(" …"))
    }

    @Test fun rejectsNonPositiveMax() {
        assertThrows(IllegalArgumentException::class.java) { TitleCleaner.clean("x", 0) }
    }
}
