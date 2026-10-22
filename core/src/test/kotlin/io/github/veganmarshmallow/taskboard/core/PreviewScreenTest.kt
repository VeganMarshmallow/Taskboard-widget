package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.preview.IssueComment
import io.github.veganmarshmallow.taskboard.core.preview.IssueDetails
import io.github.veganmarshmallow.taskboard.core.preview.PreviewError
import io.github.veganmarshmallow.taskboard.core.preview.PreviewScreen
import io.github.veganmarshmallow.taskboard.core.preview.PreviewStatus
import io.github.veganmarshmallow.taskboard.core.preview.SummaryState
import io.github.veganmarshmallow.taskboard.core.preview.TaskPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewScreenTest {
    private val board = BoardBuilder().build(
        listOf(
            issue(2, Labels.TODO, title = "Todo task"),
            issue(3, Labels.TODO, Labels.READY_FOR_CLAUDE),
            issue(4, Labels.IN_REVIEW),
            issue(5, Labels.BLOCKED, Labels.NEEDS_OWNER),
            issue(6, Labels.IN_PROGRESS),
            issue(8, Labels.BLOCKED),
        ),
        NOW,
    )

    private fun details(n: Int, vararg labels: String, body: String = "", open: Boolean = true, title: String = "T$n") =
        IssueDetails(issue(n, *labels, title = title, open = open), body)

    // --- from the cached board ---

    @Test fun fromBoardUsesTheCachedRow() {
        val p = TaskPreview.fromBoard(board, 2)!!
        assertEquals(TaskPreview(2, "Todo task", PreviewStatus.TODO, canApprove = true), p)
        assertFalse(p.isFromIssue)
    }

    @Test fun fromBoardStatusesMatchTheSections() {
        assertEquals(PreviewStatus.TODO, TaskPreview.fromBoard(board, 3)!!.status)
        assertFalse(TaskPreview.fromBoard(board, 3)!!.canApprove)
        assertEquals(PreviewStatus.IN_REVIEW, TaskPreview.fromBoard(board, 4)!!.status)
        assertEquals(PreviewStatus.NEEDS_YOU, TaskPreview.fromBoard(board, 5)!!.status)
        assertEquals(PreviewStatus.IN_PROGRESS, TaskPreview.fromBoard(board, 6)!!.status)
        assertEquals(PreviewStatus.BLOCKED, TaskPreview.fromBoard(board, 8)!!.status)
    }

    @Test fun fromBoardMissingTaskOrBoard() {
        assertNull(TaskPreview.fromBoard(board, 999))
        assertNull(TaskPreview.fromBoard(null, 2))
    }

    // --- from the loaded issue ---

    @Test fun fromIssueStatusForEveryLabelCombination() {
        fun status(vararg l: String, open: Boolean = true) = TaskPreview.fromIssue(details(9, *l, open = open)).status
        assertEquals(PreviewStatus.TODO, status())
        assertEquals(PreviewStatus.TODO, status(Labels.TODO))
        assertEquals(PreviewStatus.IN_PROGRESS, status(Labels.IN_PROGRESS))
        assertEquals(PreviewStatus.BLOCKED, status(Labels.BLOCKED))
        assertEquals(PreviewStatus.IN_REVIEW, status(Labels.IN_REVIEW))
        assertEquals(PreviewStatus.NEEDS_YOU, status(Labels.BLOCKED, Labels.NEEDS_OWNER))
        assertEquals(PreviewStatus.DONE, status(Labels.DONE))
        assertEquals(PreviewStatus.DONE, status(Labels.DONE, open = false))
        assertEquals(PreviewStatus.CLOSED, status(Labels.TODO, open = false))
        assertEquals(PreviewStatus.CLOSED, status(open = false))
    }

    @Test fun fromIssueApproveRuleMatchesTheBoard() {
        fun can(vararg l: String, open: Boolean = true) = TaskPreview.fromIssue(details(9, *l, open = open)).canApprove
        assertTrue(can(Labels.TODO))
        assertTrue(can())
        assertFalse(can(Labels.TODO, Labels.READY_FOR_CLAUDE))
        assertFalse(can(Labels.IN_PROGRESS))
        assertFalse(can(Labels.TODO, Labels.NEEDS_OWNER))
        assertFalse(can(Labels.TODO, open = false))
        assertFalse(can(Labels.DONE))
        assertFalse(TaskPreview.fromIssue(IssueDetails(issue(9, Labels.TODO, pr = true), "")).canApprove)
    }

    @Test fun fromIssueCleansTitleAndSummarises() {
        val p = TaskPreview.fromIssue(details(9, title = "  multi\nline\u202E title ", body = "### What should be done\n**Do** it"))
        assertEquals("multi line title", p.title)
        assertEquals("Do it", p.summary)
        assertTrue(p.isFromIssue)
        assertEquals("(untitled)", TaskPreview.fromIssue(details(9, title = "")).title)
        assertTrue(TaskPreview.fromIssue(details(9, title = "x".repeat(1000))).title.length <= TaskPreview.TITLE_MAX_LENGTH)
    }

    // --- screen transitions ---

    @Test fun initialShowsCachedRowWhileLoading() {
        val s = PreviewScreen.initial(2, TaskPreview.fromBoard(board, 2))
        assertTrue(s.loading)
        assertEquals("Todo task", s.task!!.title)
        assertEquals(SummaryState.NotLoaded, s.summaryState)
        assertFalse(s.showApprove) // not until the fresh labels confirm it
    }

    @Test fun nothingCachedShowsOnlyLoading() {
        val s = PreviewScreen.initial(77, null)
        assertNull(s.task)
        assertTrue(s.loading)
    }

    @Test fun loadedIssueReplacesCachedRow() {
        val s = PreviewScreen.initial(2, TaskPreview.fromBoard(board, 2))
            .issueLoaded(details(2, Labels.TODO, body = "Body text", title = "Fresh title"))
        assertFalse(s.loading)
        assertEquals("Fresh title", s.task!!.title)
        assertEquals(SummaryState.Text("Body text"), s.summaryState)
        assertTrue(s.showApprove)
    }

    @Test fun emptyBodyShowsNoDescription() {
        val s = PreviewScreen.initial(2, null).issueLoaded(details(2, body = ""))
        assertEquals(SummaryState.NoDescription, s.summaryState)
    }

    @Test fun loadFailureKeepsStaleTask() {
        val s = PreviewScreen.initial(2, TaskPreview.fromBoard(board, 2)).issueFailed(BoardError.Network)
        assertFalse(s.loading)
        assertEquals(PreviewError.Load(BoardError.Network), s.error)
        assertEquals("Todo task", s.task!!.title)
        assertTrue(s.showApprove) // cached row allowed it and nothing contradicts it
        assertEquals(SummaryState.Unavailable, s.summaryState) // not a stuck "Loading…"
        assertEquals(SummaryState.NotLoaded, s.loadStarted().summaryState)
    }

    @Test fun retryClearsTheError() {
        val s = PreviewScreen.initial(2, null).issueFailed(BoardError.Network).loadStarted()
        assertNull(s.error)
        assertTrue(s.loading)
    }

    @Test fun commentsSetOrClearTheClaudeLine() {
        val s = PreviewScreen.initial(2, null).commentsLoaded(listOf(IssueComment(1, "⛔ Blocked: why", null)))
        assertEquals("⛔ Blocked: why", s.claudeLine)
        assertNull(s.commentsLoaded(emptyList()).claudeLine)
    }

    @Test fun approveFlow() {
        val loaded = PreviewScreen.initial(2, null).issueLoaded(details(2, Labels.TODO))
        val approving = loaded.approveStarted()
        assertFalse(approving.showApprove)
        val done = approving.approveSucceeded()
        assertTrue(done.justApproved)
        assertFalse(done.showApprove)
        assertFalse(done.task!!.canApprove)
        // The reload after approval brings ready-for-claude; the button stays hidden.
        val reloaded = done.loadStarted().issueLoaded(details(2, Labels.TODO, Labels.READY_FOR_CLAUDE))
        assertFalse(reloaded.showApprove)
        assertTrue(reloaded.justApproved)
    }

    @Test fun approveFailureShowsErrorAndButtonAgain() {
        val s = PreviewScreen.initial(2, null).issueLoaded(details(2, Labels.TODO)).approveStarted().approveFailed(BoardError.IssueGone(2))
        assertEquals(PreviewError.Approve(BoardError.IssueGone(2)), s.error)
        assertTrue(s.showApprove)
        assertFalse(s.justApproved)
    }

    @Test fun approveSucceededWithoutTaskDoesNotCrash() {
        assertNull(PreviewScreen.initial(2, null).approveSucceeded().task)
    }
}
