package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.error.ErrorMessages
import io.github.veganmarshmallow.taskboard.core.error.Reachability
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetStateCodec
import io.github.veganmarshmallow.taskboard.core.task.CreateErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReachabilityTest {
    private val messages = ErrorMessages { "14:05" }

    @Test fun noNetworkWordingOnlyWhenTheDeviceIsReallyOffline() {
        assertEquals(BoardError.Network, Reachability.refine(BoardError.Network, deviceOnline = false))
        assertEquals("e.g. a DNS failure behind a VPN", BoardError.Unreachable, Reachability.refine(BoardError.Network, deviceOnline = true))
        assertEquals(BoardError.Unreachable, Reachability.refine(BoardError.Unreachable, deviceOnline = true))
        assertEquals(BoardError.Network, Reachability.refine(BoardError.Unreachable, deviceOnline = false))
    }

    @Test fun aTimeoutStaysATimeoutUnlessTheNetworkWentAway() {
        assertEquals(BoardError.Timeout, Reachability.refine(BoardError.Timeout, deviceOnline = true))
        assertEquals(BoardError.Network, Reachability.refine(BoardError.Timeout, deviceOnline = false))
    }

    @Test fun nonConnectionErrorsAreNeverChanged() {
        listOf(
            BoardError.NoToken, BoardError.BadToken(401), BoardError.BadToken(403), BoardError.NoAccess, BoardError.RateLimited(null),
            BoardError.RateLimited(NOW), BoardError.IssueGone(4), BoardError.Server(502), BoardError.Unexpected("x"),
        ).forEach { e ->
            listOf(true, false).forEach { online -> assertEquals("$e/$online", e, Reachability.refine(e, online)) }
        }
    }

    @Test fun refiningAStateKeepsTheLastGoodBoardAndTime() {
        val board = BoardBuilder().build(listOf(issue(2, "Task", "status: todo")), NOW)
        val state = WidgetState(board, BoardError.Network, NOW)
        val refined = Reachability.refine(state, deviceOnline = true)
        assertEquals(WidgetState(board, BoardError.Unreachable, NOW), refined)
        assertSame("unchanged states are returned as is", state, Reachability.refine(state, deviceOnline = false))
        val good = WidgetState(board, null, NOW)
        listOf(true, false).forEach { assertSame(good, Reachability.refine(good, it)) }
        assertSame(WidgetState.INITIAL, Reachability.refine(WidgetState.INITIAL, false))
    }

    @Test fun unreachableHasItsOwnHonestRetryMessage() {
        val m = messages.widgetMessage(BoardError.Unreachable)
        assertFalse("not the no-network wording", m.contains("No network"))
        assertTrue(m.contains("Can't reach GitHub"))
        assertTrue(m.endsWith("tap to retry"))
        assertFalse(m.contains('\n'))
        assertFalse(messages.opensSetup(BoardError.Unreachable))
        assertTrue(messages.message(BoardError.Network).startsWith("No network"))
    }

    @Test fun unreachableIsStoredAndMayMeanACreatedIssue() {
        val state = WidgetState(null, BoardError.Unreachable, NOW)
        assertEquals(state, WidgetStateCodec.decode(WidgetStateCodec.encode(state)))
        assertTrue(CreateErrors.mayAlreadyExist(BoardError.Unreachable))
    }
}
