package io.github.veganmarshmallow.taskboard

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.HttpClient
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.task.NewTaskStore
import io.github.veganmarshmallow.taskboard.core.task.SubmitMarker
import io.github.veganmarshmallow.taskboard.core.task.SubmitMarkerCodec
import io.github.veganmarshmallow.taskboard.core.task.SubmitRecovery
import io.github.veganmarshmallow.taskboard.newtask.NewTaskController
import io.github.veganmarshmallow.taskboard.newtask.NewTaskDeps
import io.github.veganmarshmallow.taskboard.newtask.RepoSuggestionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    private fun sendAndWait(controller: NewTaskController): Outcome<*>? {
        instrumentation.runOnMainSync { controller.send(context, sendableIssue(controller.session)) }
        waitUntil("send finished", onMainThread = true) { !controller.session.isSubmitting }
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
        waitUntil("check finished", onMainThread = true) { controller.checking == null }
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
            waitUntil("check finished", onMainThread = true) { controller.checking == null }
        }
        var result: SubmitRecovery.Result? = null
        instrumentation.runOnMainSync { result = controller.takeRecovery() }
        assertEquals(SubmitRecovery.Result.CheckFailed("Left behind", BoardError.NoToken), result)
        assertEquals(2, calls.get())
    }

    /** The suggestions thread had the same hole: an Error there loads nothing, and the next load still runs. */
    @Test fun anErrorWhileLoadingSuggestionsIsSilentAndTheNextLoadStillRuns() {
        val prefs = context.getSharedPreferences("repo_suggestions", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val calls = AtomicInteger()
            val http = HttpClient {
                if (calls.incrementAndGet() == 1) throw NoSuchMethodError("x")
                HttpResponse(200, body = """[{"number":3,"body":"### Repo\n\nVeganMarshmallow/Taskboard-widget\n"}]""")
            }
            val api = GitHubApi(http, "github_pat_X", Clock.fixed(FakeBoards.NOW, ZoneOffset.UTC))
            val suggestions = RepoSuggestionStore(context, NewTaskDeps.standard { api })
            val loads = AtomicInteger()
            val loaded = CountDownLatch(1)
            var fresh: List<String> = emptyList()
            suggestions.refresh { loads.incrementAndGet() }
            suggestions.refresh { fresh = it; loads.incrementAndGet(); loaded.countDown() }
            assertTrue("the second load ran on the same thread", loaded.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertEquals("the crashed load reported nothing", 1, loads.get())
            assertEquals(2, calls.get())
            assertTrue(fresh.isNotEmpty())
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
