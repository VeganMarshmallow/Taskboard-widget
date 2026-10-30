package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.preview.IssueDetails
import io.github.veganmarshmallow.taskboard.core.preview.PreviewScreen
import io.github.veganmarshmallow.taskboard.core.preview.TaskPreview
import io.github.veganmarshmallow.taskboard.core.reply.ReplyAction
import io.github.veganmarshmallow.taskboard.core.reply.ReplyForm
import io.github.veganmarshmallow.taskboard.core.reply.ReplyResult
import io.github.veganmarshmallow.taskboard.core.reply.ReplyStep
import io.github.veganmarshmallow.taskboard.core.reply.ReplyText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task #44: the reply box's state. */
class ReplyFormTest {
    private val failedComment = ReplyResult.Failed(ReplyStep.COMMENT, BoardError.Network)
    private val failedUnblock = ReplyResult.Failed(ReplyStep.UNBLOCK, BoardError.Server(502))

    @Test fun defaultsToUnblockAndDisabledSend() {
        val form = ReplyForm()
        assertTrue(form.unblock)
        assertFalse(form.canSend)
        assertNull(form.start(offerUnblock = true))
    }

    @Test fun whitespaceDisablesSend() {
        listOf("", " ", "\n\n", "\t ").forEach { assertFalse(it, ReplyForm().edit(it).canSend) }
        assertTrue(ReplyForm().edit(" x ").canSend)
    }

    @Test fun tooLongDisablesSend() {
        val atLimit = ReplyForm().edit("a".repeat(ReplyText.MAX_LENGTH))
        assertTrue(atLimit.canSend)
        assertFalse(atLimit.tooLong)
        val over = atLimit.edit(atLimit.draft + "a")
        assertFalse(over.canSend)
        assertTrue(over.tooLong)
    }

    @Test fun startSendsTheCleanedTextAndUnblocksOnlyWhenOffered() {
        val form = ReplyForm().edit("  Use ECharts\n")
        assertEquals(ReplyAction.Post("Use ECharts", unblock = true), form.start(offerUnblock = true)!!.action)
        assertEquals(ReplyAction.Post("Use ECharts", unblock = false), form.start(offerUnblock = false)!!.action)
        assertEquals(ReplyAction.Post("Use ECharts", unblock = false), form.unblockChecked(false).start(offerUnblock = true)!!.action)
    }

    @Test fun doubleTapSendsOnce() {
        val started = ReplyForm().edit("yes").start(offerUnblock = true)!!.form
        assertTrue(started.sending)
        assertFalse(started.canSend)
        assertNull(started.start(offerUnblock = true))
    }

    @Test fun successClearsTheDraftAndKeepsTheCheckboxChoice() {
        val done = ReplyForm(unblock = false).edit("yes").start(true)!!.form.finished(ReplyResult.Sent(unblocked = false))
        assertEquals("", done.draft)
        assertFalse(done.sending)
        assertFalse(done.unblock)
        assertEquals(ReplyResult.Sent(false), done.sent)
    }

    @Test fun commentFailureKeepsTheTextForAnotherTry() {
        val failed = ReplyForm().edit("yes").start(true)!!.form.finished(failedComment)
        assertEquals("yes", failed.draft)
        assertEquals(failedComment, failed.failure)
        assertTrue(failed.canSend)
        assertTrue(failed.failure!!.mayHavePosted)
        val again = failed.start(true)!!
        assertNull("starting again hides the old error", again.form.failure)
        assertEquals(ReplyAction.Post("yes", true), again.action)
    }

    @Test fun unblockFailureRetriesOnlyTheLabels() {
        val failed = ReplyForm().edit("yes").start(true)!!.form.finished(failedUnblock)
        assertEquals("", failed.draft)
        assertTrue(failed.pendingUnblock)
        assertTrue(failed.retriesUnblock)
        assertTrue(failed.canSend)
        assertEquals(ReplyAction.Unblock, failed.start(offerUnblock = false)!!.action)
        val done = failed.start(false)!!.form.finished(ReplyResult.Sent(unblocked = true))
        assertFalse(done.pendingUnblock)
    }

    @Test fun aNewReplyAfterAFailedUnblockStillUnblocks() {
        val failed = ReplyForm().edit("yes").start(true)!!.form.finished(failedUnblock).unblockChecked(false)
        assertEquals(ReplyAction.Post("also this", unblock = true), failed.edit("also this").start(false)!!.action)
    }

    // --- in the preview screen ---

    private fun details(vararg labels: String, open: Boolean = true) =
        IssueDetails(issue(7, *labels, open = open), "")

    @Test fun needsOwnerTaskOffersUnblock() {
        val screen = PreviewScreen.initial(7, null).issueLoaded(details(Labels.BLOCKED, Labels.NEEDS_OWNER))
        assertTrue(screen.task!!.needsOwner)
        assertTrue(screen.offerUnblock)
        assertTrue(screen.showReply)
    }

    @Test fun otherTasksReplyWithoutUnblock() {
        val screen = PreviewScreen.initial(7, null).issueLoaded(details(Labels.IN_PROGRESS))
        assertFalse(screen.offerUnblock)
        assertTrue(screen.showReply)
    }

    @Test fun noReplyBoxForClosedOrUnknownTasks() {
        assertFalse(PreviewScreen.initial(7, null).showReply)
        assertFalse(PreviewScreen.initial(7, null).issueLoaded(details(Labels.DONE, open = false)).showReply)
    }

    @Test fun cachedRowKnowsNeedsOwner() {
        val board: Board = BoardBuilder().build(listOf(issue(7, Labels.BLOCKED, Labels.NEEDS_OWNER)), NOW)
        assertTrue(TaskPreview.fromBoard(board, 7)!!.needsOwner)
    }

    @Test fun replySurvivesReloads() {
        val typed = PreviewScreen.initial(7, null).withReply(ReplyForm().edit("draft"))
        val reloaded = typed.loadStarted().issueLoaded(details(Labels.TODO)).commentsLoaded(emptyList())
        assertEquals("draft", reloaded.reply.draft)
    }

    @Test fun pendingUnblockKeepsTheCheckboxAfterLabelsChanged() {
        val screen = PreviewScreen.initial(7, null).issueLoaded(details(Labels.TODO))
            .withReply(ReplyForm(pendingUnblock = true))
        assertNotNull(screen.task)
        assertTrue(screen.offerUnblock)
    }
}
