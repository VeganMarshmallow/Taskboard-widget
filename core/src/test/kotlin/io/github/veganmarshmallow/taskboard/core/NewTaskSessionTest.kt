package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.model.Priority
import io.github.veganmarshmallow.taskboard.core.task.CreatedIssue
import io.github.veganmarshmallow.taskboard.core.task.NewTaskSession
import io.github.veganmarshmallow.taskboard.core.task.NewTaskSession.Start
import io.github.veganmarshmallow.taskboard.core.task.TaskDraft
import io.github.veganmarshmallow.taskboard.core.task.TaskDraftValidator.Field
import io.github.veganmarshmallow.taskboard.core.task.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewTaskSessionTest {
    private val draft = TaskDraft("כותרת", "מה לעשות", "", "app", TaskType.BUG, Priority.LOW, approveNow = true)

    @Test fun validDraftStartsOnceUntilComplete() {
        val s = NewTaskSession()
        val send = s.start(draft) as Start.Send
        assertEquals("כותרת", send.issue.title)
        assertTrue(s.isSubmitting)
        assertEquals(Start.Busy, s.start(draft)) // double tap
        s.complete(Outcome.Success(CreatedIssue(5)))
        assertFalse(s.isSubmitting)
        assertTrue(s.start(TaskDraft(title = "next")) is Start.Send)
    }

    @Test fun invalidDraftIsKeptAndDoesNotLock() {
        val s = NewTaskSession()
        val bad = draft.copy(title = " ", repo = "a/b/c")
        assertEquals(setOf(Field.TITLE, Field.REPO), (s.start(bad) as Start.Invalid).errors.keys)
        assertEquals(bad, s.draft)
        assertFalse(s.isSubmitting)
    }

    @Test fun successClearsTheDraftFailureKeepsIt() {
        val s = NewTaskSession()
        s.start(draft)
        s.complete(Outcome.Failure(BoardError.Network))
        assertEquals(draft, s.draft)
        s.start(draft)
        s.complete(Outcome.Success(CreatedIssue(9)))
        assertEquals(TaskDraft.EMPTY, s.draft)
    }

    @Test fun resultIsTakenOnce() {
        val s = NewTaskSession()
        assertNull(s.takeResult())
        s.start(draft)
        s.complete(Outcome.Failure(BoardError.NoAccess))
        assertEquals(Outcome.Failure(BoardError.NoAccess), s.takeResult())
        assertNull(s.takeResult())
    }

    @Test fun discardIsIgnoredWhileSubmitting() {
        val s = NewTaskSession()
        s.start(draft)
        s.discard()
        assertEquals(draft, s.draft)
        s.complete(Outcome.Failure(BoardError.Network))
        s.discard()
        assertEquals(TaskDraft.EMPTY, s.draft)
    }

    @Test fun busyStillStoresTheLatestDraft() {
        val s = NewTaskSession()
        s.start(draft)
        val edited = draft.copy(title = "edited while sending")
        assertEquals(Start.Busy, s.start(edited))
        assertEquals(edited, s.draft)
    }

    @Test fun aNewFormDropsAShownSuccessButKeepsAFailure() {
        val s = NewTaskSession()
        s.start(draft); s.complete(Outcome.Success(CreatedIssue(1)))
        s.dropShownSuccess()
        assertNull(s.takeResult())
        s.start(draft); s.complete(Outcome.Failure(BoardError.Network))
        s.dropShownSuccess()
        assertEquals(Outcome.Failure(BoardError.Network), s.takeResult())
    }
}
