package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.BoardLoader
import io.github.veganmarshmallow.taskboard.core.board.TitleCleaner
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.HttpClient
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetStateCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reviewer-added edge cases: cached state after process death, title cleaning. */
class StateEdgeCasesTest {
    // --- cached state (survives process death through SharedPreferences) --------------------

    @Test fun hugeBoardRoundTripsThroughTheCache() {
        val issues = (2..2001).map { issue(it, Labels.TODO, title = "משימה $it 🚀 " + "x".repeat(150)) }
        val state = WidgetState(BoardBuilder().build(issues, NOW), BoardError.Network, NOW)
        val text = WidgetStateCodec.encode(state)
        assertTrue("capped board keeps the cache small: ${text.length}", text.length < 50_000)
        assertEquals(state, WidgetStateCodec.decode(text))
    }

    @Test fun issueGoneErrorRoundTrips() {
        val state = WidgetState(null, BoardError.IssueGone(9), NOW)
        assertEquals(state, WidgetStateCodec.decode(WidgetStateCodec.encode(state)))
    }

    @Test fun cachedTitlesAreValidForXmlStorage() {
        // SharedPreferences is an XML file: lone surrogates and C0 controls would corrupt it.
        val raw = "bad \uD800 \u0001 ￿ end"
        val board = BoardBuilder().build(listOf(issue(2, Labels.TODO, title = raw)), NOW)
        val encoded = WidgetStateCodec.encode(WidgetState(board, null, NOW))
        encoded.forEachIndexed { i, c ->
            assertFalse("control char at $i", c < ' ')
            assertFalse("noncharacter at $i", c == '￿' || c == '￾')
            if (Character.isHighSurrogate(c)) assertTrue(Character.isLowSurrogate(encoded[i + 1]))
            if (Character.isLowSurrogate(c)) assertTrue(Character.isHighSurrogate(encoded[i - 1]))
        }
    }

    @Test fun partiallyCorruptStateFallsBackToInitial() {
        val good = WidgetStateCodec.encode(WidgetState(BoardBuilder().build(listOf(issue(2, Labels.TODO)), NOW), null, NOW))
        listOf(
            good.substring(0, good.length / 2), // truncated write
            good.replace("\"TODO\"", "\"ARCHIVED\""), // unknown section after a downgrade
            good.replace("\"NORMAL\"", "\"URGENT\""), // unknown priority
            good.replace("\"a\":true", "\"a\":\"yes\"").replace("\"a\":false", "\"a\":\"no\""), // wrong type
            good.replace("\"t\":\"Task 2\"", "\"t\":null"), // null title
        ).forEach { assertEquals(it, WidgetState.INITIAL, WidgetStateCodec.decode(it)) }
    }

    @Test fun decodeOfUnknownTopLevelFieldsStillWorks() {
        val text = """{"v":3,"future":{"x":1},"board":null,"error":{"k":"Network","extra":true},"attemptedAt":5}"""
        val state = WidgetStateCodec.decode(text)
        assertEquals(BoardError.Network, state.error)
        assertNull(state.board)
    }

    // --- loader --------------------------------------------------------------------------

    @Test fun loaderNeverThrowsWhenTheApiBlowsUp() {
        val exploding = HttpClient { throw IllegalStateException("boom") }
        val previous = WidgetState(BoardBuilder().build(listOf(issue(2, Labels.TODO)), NOW), null, NOW)
        val state = BoardLoader(FIXED_CLOCK).refresh(previous, GitHubApi(exploding, TOKEN, FIXED_CLOCK))
        assertTrue(state.error is BoardError.Unexpected)
        assertEquals(previous.board, state.board) // stale board kept under the banner
    }

    @Test fun loaderFromInitialStateWithErrorHasNoBoard() {
        val failing = GitHubApi(FakeHttp().onUrlContaining("issues", status(401)), TOKEN, FIXED_CLOCK)
        val state = BoardLoader(FIXED_CLOCK).refresh(WidgetState.INITIAL, failing)
        assertNull(state.board)
        assertEquals(BoardError.BadToken(401), state.error)
        assertEquals(NOW, state.attemptedAt)
    }

    @Test fun loaderOnSuccessWithEmptyBoardStoresAnEmptyBoard() {
        val working = GitHubApi(FakeHttp().onUrlContaining("issues", ok("[]")), TOKEN, FIXED_CLOCK)
        val state = BoardLoader(FIXED_CLOCK).refresh(WidgetState(null, BoardError.NoToken, NOW), working)
        assertTrue(state.board!!.isEmpty)
        assertNull(state.error)
    }

    // --- titles ----------------------------------------------------------------------------

    @Test fun bidiOverridesAreStrippedButHebrewIsKept() {
        assertEquals("abc", TitleCleaner.clean("‮abc‬"))
        assertEquals("שלום עולם", TitleCleaner.clean("⁧שלום עולם⁩"))
        assertEquals("Fix #12 באג בכפתור", TitleCleaner.clean("Fix #12 באג בכפתור"))
        assertEquals(TitleCleaner.UNTITLED, TitleCleaner.clean("‮‭⁦"))
    }

    @Test fun zeroWidthJoinerIsNotLeftDanglingAtTheCut() {
        val family = "👩‍👩‍👧‍👦"
        for (max in 2..30) {
            val out = TitleCleaner.clean("a" + family.repeat(5), max)
            assertFalse("max=$max '$out'", out.dropLast(1).endsWith("‍"))
            assertTrue(out.length <= max)
        }
    }

    @Test fun unicodeLineAndParagraphSeparatorsCollapse() {
        assertEquals("a b c", TitleCleaner.clean("a b c"))
        assertEquals("a b", TitleCleaner.clean("a  b"))
    }

    @Test fun maxLengthOneIsJustTheEllipsis() {
        assertEquals("…", TitleCleaner.clean("abc", 1))
        assertEquals("a", TitleCleaner.clean("a", 1))
    }
}
