package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.BoardLoader
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetStateCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetStateTest {
    private val board = BoardBuilder().build(
        listOf(
            issue(2, Labels.TODO, Labels.PRIORITY_HIGH, title = "📋 \"quoted\" \\ title ✅"),
            issue(3, Labels.IN_REVIEW), issue(4, Labels.NEEDS_OWNER), issue(5, Labels.BLOCKED),
            issue(6, Labels.IN_PROGRESS, Labels.PRIORITY_LOW),
            issue(7, Labels.DONE, open = false, closedAt = NOW),
        ),
        NOW,
    )

    @Test fun codecRoundTripsBoardAndEveryError() {
        val errors = listOf(
            null, BoardError.NoToken, BoardError.BadToken(401), BoardError.NoAccess,
            BoardError.RateLimited(NOW), BoardError.RateLimited(null), BoardError.Network,
            BoardError.Server(503), BoardError.Unexpected("x \"y\""),
        )
        errors.forEach { error ->
            val state = WidgetState(board, error, NOW)
            assertEquals(state, WidgetStateCodec.decode(WidgetStateCodec.encode(state)))
        }
        assertEquals(WidgetState.INITIAL, WidgetStateCodec.decode(WidgetStateCodec.encode(WidgetState.INITIAL)))
    }

    @Test fun decodeNeverThrowsOnGarbage() {
        listOf(null, "", "garbage", "[]", "{}", "{\"v\":2}", "{\"v\":1,\"board\":{\"sections\":7}}",
            "{\"v\":1,\"error\":{\"k\":\"Martian\"}}").forEach {
            assertEquals(WidgetState.INITIAL, WidgetStateCodec.decode(it))
        }
    }

    @Test fun decodeFillsMissingSections() {
        val text = """{"v":1,"board":{"fetchedAt":0,"done":2,"sections":[]},"error":null,"attemptedAt":null}"""
        val decoded = WidgetStateCodec.decode(text).board!!
        assertEquals(Section.entries.toList(), decoded.sections.map { it.section })
        assertEquals(2, decoded.doneLast7Days)
    }

    @Test fun loaderWithoutTokenReportsNoToken() {
        val state = BoardLoader(FIXED_CLOCK).refresh(WidgetState(board, null, NOW), null)
        assertEquals(BoardError.NoToken, state.error)
        assertNull(state.board) // don't keep showing a board for a removed token
    }

    @Test fun loaderKeepsLastBoardOnFailureAndClearsErrorOnSuccess() {
        val failing = GitHubApi(FakeHttp().onUrlContaining("issues", status(500)), TOKEN, FIXED_CLOCK)
        val afterFail = BoardLoader(FIXED_CLOCK).refresh(WidgetState(board, null, NOW), failing)
        assertEquals(board, afterFail.board)
        assertEquals(BoardError.Server(500), afterFail.error)

        val working = GitHubApi(FakeHttp().onUrlContaining("issues", ok(page(issueJson(8, "status: todo")))), TOKEN, FIXED_CLOCK)
        val afterOk = BoardLoader(FIXED_CLOCK).refresh(afterFail, working)
        assertNull(afterOk.error)
        assertEquals(listOf(8), afterOk.board!!.section(Section.TODO).items.map { it.number })
        assertEquals(NOW, afterOk.attemptedAt)
    }
}
