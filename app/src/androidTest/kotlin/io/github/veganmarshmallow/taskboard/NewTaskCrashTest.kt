package io.github.veganmarshmallow.taskboard

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.task.NewIssue
import io.github.veganmarshmallow.taskboard.core.task.NewTaskSession
import io.github.veganmarshmallow.taskboard.core.task.NewTaskStore
import io.github.veganmarshmallow.taskboard.core.task.SubmitMarker
import io.github.veganmarshmallow.taskboard.core.task.SubmitMarkerCodec
import io.github.veganmarshmallow.taskboard.core.task.SubmitRecovery
import io.github.veganmarshmallow.taskboard.core.task.TaskDraft
import io.github.veganmarshmallow.taskboard.newtask.NewTaskController
import io.github.veganmarshmallow.taskboard.newtask.NewTaskDeps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * Task #56: an [Error] (not just a RuntimeException) thrown while sending a new task or while
 * checking a leftover submit is reported as a failure on the real background thread, instead of
 * escaping it and crashing the app; and that thread still runs the next request.
 */
@RunWith(AndroidJUnit4::class)
class NewTaskCrashTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private class MemoryStore : NewTaskStore {
        @Volatile override var draft: String? = null
        @Volatile override var marker: String? = null
    }

    private val store = MemoryStore()

    /** Real send and main threads (as in the app), with [apiProvider] and an in-memory store. */
    private fun controller(apiProvider: (Context) -> GitHubApi?): NewTaskController {
        val real = NewTaskDeps.standard(apiProvider)
        val deps = NewTaskDeps(
            apiProvider, real.sendExecutor, real.suggestionsExecutor, real.mainThread,
            store = { store }, clock = Clock.fixed(FakeBoards.NOW, ZoneOffset.UTC),
        )
        return NewTaskController(deps)
    }

    private fun issue(session: NewTaskSession): NewIssue =
        (session.start(TaskDraft(title = "A task", repo = "Taskboard-widget")) as NewTaskSession.Start.Send).issue

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            var met = false
            instrumentation.runOnMainSync { met = condition() }
            if (met) return
            assertTrue("timed out: $what", System.currentTimeMillis() < deadline)
            Thread.sleep(50)
        }
    }

    private fun sendAndWait(controller: NewTaskController): Outcome<*>? {
        instrumentation.runOnMainSync { controller.send(context, issue(controller.session)) }
        waitUntil("send finished") { !controller.session.isSubmitting }
        var result: Outcome<*>? = null
        instrumentation.runOnMainSync { result = controller.session.takeResult() }
        return result
    }

    @Test fun anErrorDuringSendIsShownAsAnInternalFailureAndTheFormUnlocks() {
        val controller = controller { throw NoSuchMethodError("createIssue") }
        assertEquals(Outcome.Failure(BoardError.Internal("NoSuchMethodError")), sendAndWait(controller))
        assertFalse(controller.session.isSubmitting)
        assertEquals("the draft is kept for another try", "A task", controller.session.draft.title)
        assertNull("a crash is not 'may already exist': no recovery check next time", store.marker)
    }

    @Test fun theSendThreadSurvivesAnErrorAndRunsTheNextSend() {
        val calls = AtomicInteger()
        val controller = controller { if (calls.incrementAndGet() == 1) throw NoSuchMethodError("x") else null }
        assertTrue(sendAndWait(controller) is Outcome.Failure)
        assertEquals("the next send ran on the same thread", Outcome.Failure(BoardError.NoToken), sendAndWait(controller))
        assertEquals(2, calls.get())
    }

    /** The interrupt flag [guarded] restores must not leak into, and break, the next send on that thread. */
    @Test fun anInterruptedSendFailsAndTheNextSendStillRuns() {
        val calls = AtomicInteger()
        val controller = controller { if (calls.incrementAndGet() == 1) throw InterruptedException() else null }
        assertEquals(Outcome.Failure(BoardError.Internal("InterruptedException")), sendAndWait(controller))
        assertEquals(Outcome.Failure(BoardError.NoToken), sendAndWait(controller))
        assertEquals(2, calls.get())
    }

    @Test fun aRuntimeExceptionDuringSendIsStillAFailure() {
        val controller = controller { throw IllegalStateException("token github_pat_X") }
        assertEquals(Outcome.Failure(BoardError.Internal("IllegalStateException")), sendAndWait(controller))
        assertNull(store.marker)
    }

    @Test fun anErrorDuringTheRecoveryCheckIsAFailedCheckThatKeepsTheMarker() {
        val marker = SubmitMarkerCodec.encode(SubmitMarker("Left behind", FakeBoards.NOW))
        store.marker = marker
        val controller = controller { throw NoSuchMethodError("issuesUpdatedSince") }
        instrumentation.runOnMainSync { controller.restore(context) }
        waitUntil("check finished") { controller.checking == null }
        var result: SubmitRecovery.Result? = null
        instrumentation.runOnMainSync { result = controller.takeRecovery() }
        assertNotNull(result)
        assertEquals(SubmitRecovery.Result.CheckFailed("Left behind", BoardError.Internal("NoSuchMethodError")), result)
        assertEquals("can't tell, so the marker stays for the next check", marker, store.marker)
        assertFalse("the form is usable again", controller.session.isSubmitting)
    }

    @Test fun theRecoveryCheckRunsAgainAfterAnError() {
        store.marker = SubmitMarkerCodec.encode(SubmitMarker("Left behind", FakeBoards.NOW))
        val calls = AtomicInteger()
        val controller = controller { if (calls.incrementAndGet() == 1) throw NoSuchMethodError("x") else null }
        repeat(2) {
            instrumentation.runOnMainSync { controller.restore(context) }
            waitUntil("check finished") { controller.checking == null }
        }
        var result: SubmitRecovery.Result? = null
        instrumentation.runOnMainSync { result = controller.takeRecovery() }
        assertEquals(SubmitRecovery.Result.CheckFailed("Left behind", BoardError.NoToken), result)
        assertEquals(2, calls.get())
    }
}
