package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.reply.ReplyForm
import io.github.veganmarshmallow.taskboard.core.reply.ReplyMemory
import io.github.veganmarshmallow.taskboard.core.reply.ReplyResult
import io.github.veganmarshmallow.taskboard.core.reply.ReplyStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task #44: the reply box between preview sheets (closed by a stray tap, reopened, recreated). */
class ReplyMemoryTest {
    private val memory = ReplyMemory()

    private fun sending(text: String = "yes"): ReplyForm = ReplyForm().edit(text).start(offerUnblock = true)!!.form

    @Test fun unknownIssueOpensEmpty() {
        assertFalse(memory.knows(7))
        assertEquals(ReplyForm(), memory.form(7))
    }

    @Test fun draftAndChoicesComeBackButNotTheLastMessage() {
        val failed = ReplyForm(unblock = false).edit("half an answer")
            .finished(ReplyResult.Failed(ReplyStep.COMMENT, BoardError.Network))
        memory.remember(7, failed)
        val reopened = memory.form(7)
        assertTrue(memory.knows(7))
        assertEquals("half an answer", reopened.draft)
        assertFalse(reopened.unblock)
        assertNull(reopened.failure)
        assertEquals(ReplyForm(), memory.form(8))
    }

    @Test fun sheetReopenedWhileSendingCannotSendAgain() {
        memory.remember(7, sending())
        val reopened = memory.form(7)
        assertTrue(reopened.sending)
        assertNull("a second sheet must not post the same reply again", reopened.start(offerUnblock = true))
    }

    @Test fun resultReachesTheSheetOpenNowAndClearsThePostedDraft() {
        memory.remember(7, sending())
        val old: (ReplyForm, ReplyResult) -> Unit = { _, _ -> throw AssertionError("the closed sheet must not hear it") }
        memory.listen(7, old)
        var heard: Pair<ReplyForm, ReplyResult>? = null
        val current: (ReplyForm, ReplyResult) -> Unit = { form, result -> heard = form to result }
        memory.listen(7, current) // the reopened sheet
        memory.stopListening(7, old) // the old sheet's onDestroy arrives late
        memory.finished(7, ReplyResult.Sent(unblocked = true))
        assertEquals(ReplyResult.Sent(true), heard!!.second)
        assertEquals("", heard!!.first.draft)
        assertFalse(heard!!.first.sending)
        assertEquals("", memory.form(7).draft)
        assertFalse(memory.form(7).sending)
    }

    @Test fun resultWithNoSheetOpenIsStillRemembered() {
        memory.remember(7, sending("keep me"))
        memory.finished(7, ReplyResult.Failed(ReplyStep.COMMENT, BoardError.Timeout))
        assertEquals("keep me", memory.form(7).draft)
        assertTrue(memory.form(7).canSend)

        memory.remember(8, sending("posted"))
        memory.finished(8, ReplyResult.Failed(ReplyStep.UNBLOCK, BoardError.Server(502)))
        assertEquals("", memory.form(8).draft)
        assertTrue("the label retry is still owed", memory.form(8).retriesLabels)
    }

    @Test fun stoppingTheCurrentListenerSilencesIt() {
        val calls = mutableListOf<ReplyResult>()
        val listener: (ReplyForm, ReplyResult) -> Unit = { _, r -> calls += r }
        memory.listen(7, listener)
        memory.stopListening(7, listener)
        memory.finished(7, ReplyResult.Sent(false))
        assertTrue(calls.isEmpty())
        assertFalse(memory.form(7).sending)
    }

    @Test fun postedSaysWhetherTheCommentIsOnGitHub() {
        assertTrue(ReplyResult.Sent(false).posted)
        assertTrue(ReplyResult.Failed(ReplyStep.UNBLOCK, BoardError.Network).posted)
        assertFalse(ReplyResult.Failed(ReplyStep.COMMENT, BoardError.Network).posted)
    }
}
