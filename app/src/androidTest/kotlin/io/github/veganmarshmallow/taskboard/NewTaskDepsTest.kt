package io.github.veganmarshmallow.taskboard

import android.content.Context
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.HttpClient
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.task.NewIssue
import io.github.veganmarshmallow.taskboard.core.task.NewTaskSession
import io.github.veganmarshmallow.taskboard.core.task.TaskDraft
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.newtask.NewTaskController
import io.github.veganmarshmallow.taskboard.newtask.NewTaskDeps
import io.github.veganmarshmallow.taskboard.newtask.RepoSuggestionStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [NewTaskController] and [RepoSuggestionStore] with injected [NewTaskDeps]: no token, crashes,
 * main-thread delivery, late results after the form closed, suggestions vs submit, and [NewTaskRule].
 */
@RunWith(AndroidJUnit4::class)
class NewTaskDepsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** GETs (suggestions) and POSTs (create) wait for their latch when set. */
    private class FakeHttp : HttpClient {
        @Volatile var getGate: CountDownLatch? = null
        @Volatile var postGate: CountDownLatch? = null
        @Volatile var getResponse: () -> HttpResponse =
            { HttpResponse(200, body = """[{"number":3,"body":"### Repo\n\nVeganMarshmallow/Taskboard-widget\n"}]""") }
        @Volatile var postResponse: () -> HttpResponse = { HttpResponse(201, body = """{"number":77,"labels":[]}""") }

        override fun execute(request: HttpRequest): HttpResponse {
            val gate = if (request.method == "POST") postGate else getGate
            gate?.await(10, TimeUnit.SECONDS)
            return if (request.method == "POST") postResponse() else getResponse()
        }
    }

    private val http = FakeHttp()
    private val api: (Context) -> GitHubApi? = { GitHubApi(http, "github_pat_X", Clock.fixed(FakeBoards.NOW, ZoneOffset.UTC)) }
    private val direct = Executor { it.run() }

    private fun syncDeps(apiProvider: (Context) -> GitHubApi?, suggestions: Executor = direct) =
        NewTaskDeps(apiProvider, sendExecutor = direct, suggestionsExecutor = suggestions, mainThread = direct)

    private fun issue(session: NewTaskSession): NewIssue =
        (session.start(TaskDraft(title = "A task", repo = "Taskboard-widget")) as NewTaskSession.Start.Send).issue

    /** Sends synchronously on the main thread (effects toast, so they need a looper). */
    private fun sendNow(controller: NewTaskController) =
        instrumentation.runOnMainSync { controller.send(context, issue(controller.session)) }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            assertTrue("timed out: $what", System.currentTimeMillis() < deadline)
            Thread.sleep(50)
            instrumentation.waitForIdleSync()
        }
    }

    private fun clearSuggestionCache() =
        context.getSharedPreferences("repo_suggestions", Context.MODE_PRIVATE).edit().clear().commit()

    @Before fun freshCache() { clearSuggestionCache() }

    @After fun releaseGates() {
        http.getGate?.countDown()
        http.postGate?.countDown()
        clearSuggestionCache()
    }

    @Test fun noTokenFailsAndKeepsDraftAndResultForTheReopenedForm() {
        val controller = NewTaskController(syncDeps({ null }))
        sendNow(controller)
        assertEquals(Outcome.Failure(BoardError.NoToken), controller.session.takeResult())
        assertFalse(controller.session.isSubmitting)
        assertEquals("A task", controller.session.draft.title)
    }

    @Test fun crashingApiBecomesAnUnexpectedFailureAndUnlocksSubmit() {
        val controller = NewTaskController(syncDeps({ throw IllegalStateException("boom") }))
        sendNow(controller)
        assertEquals(
            Outcome.Failure(BoardError.Unexpected("internal error: IllegalStateException")),
            controller.session.takeResult(),
        )
        assertFalse(controller.session.isSubmitting)
    }

    @Test fun backgroundResultReachesTheListenerOnTheMainThread() {
        val controller = NewTaskController(NewTaskDeps.standard(api))
        val delivered = CountDownLatch(1)
        var onMain = false
        instrumentation.runOnMainSync {
            controller.listener = { onMain = Looper.myLooper() == Looper.getMainLooper(); delivered.countDown() }
            controller.send(context, issue(controller.session))
        }
        assertTrue("listener called", delivered.await(10, TimeUnit.SECONDS))
        assertTrue(onMain)
        instrumentation.runOnMainSync {
            assertTrue(controller.session.takeResult() is Outcome.Success)
            assertEquals(TaskDraft.EMPTY, controller.session.draft)
            controller.listener = null
        }
    }

    @Test fun lateResultAfterTheFormClosedIsKeptAndCallsNoOldListener() {
        http.postGate = CountDownLatch(1)
        http.postResponse = { HttpResponse(500, body = "") }
        val controller = NewTaskController(NewTaskDeps.standard(api))
        val calls = AtomicInteger()
        instrumentation.runOnMainSync {
            controller.listener = { calls.incrementAndGet() }
            controller.send(context, issue(controller.session))
            controller.listener = null // the form's onPause: Activity gone before the result
        }
        http.postGate!!.countDown()
        waitUntil("request finishes") { !controller.session.isSubmitting }
        assertEquals(0, calls.get())
        instrumentation.runOnMainSync {
            assertTrue(controller.session.takeResult() is Outcome.Failure)
            assertEquals("A task", controller.session.draft.title)
        }
    }

    @Test fun stuckSuggestionsDontDelayASubmit() {
        http.getGate = CountDownLatch(1)
        val deps = NewTaskDeps.standard(api)
        val controller = NewTaskController(deps)
        RepoSuggestionStore(context, deps).refresh { }
        instrumentation.runOnMainSync { controller.send(context, issue(controller.session)) }
        waitUntil("submit finishes while suggestions hang") { !controller.session.isSubmitting }
        instrumentation.runOnMainSync { assertTrue(controller.session.takeResult() is Outcome.Success) }
    }

    @Test fun suggestionsWithoutATokenDoNothing() {
        val used = AtomicInteger()
        val store = RepoSuggestionStore(context, syncDeps({ null }, suggestions = Executor { used.incrementAndGet(); it.run() }))
        store.refresh { fail("no callback without a token") }
        assertEquals(0, used.get())
        assertEquals(emptyList<String>(), store.cached())
    }

    @Test fun suggestionsCallBackOnlyWhenChangedAndFailuresKeepTheCache() {
        val store = RepoSuggestionStore(context, syncDeps(api))
        val loaded = mutableListOf<List<String>>()
        store.refresh { loaded += it }
        assertEquals(1, loaded.size)
        assertTrue(loaded.single().isNotEmpty())
        assertEquals(loaded.single(), store.cached())

        store.refresh { loaded += it } // same list again
        http.getResponse = { HttpResponse(500, body = "") }
        store.refresh { loaded += it } // failure is silent
        http.getResponse = { throw IllegalStateException("boom") }
        store.refresh { loaded += it } // so is a crash
        assertEquals(1, loaded.size)
        assertEquals(loaded.single(), store.cached())
    }

    @Test fun ruleSwapsInAFreshControllerAndRestoresTheAppOneWhenTheTestThrows() {
        val appController = AppGraph.newTask
        val rule = NewTaskRule { syncDeps({ null }) }
        var during: NewTaskController? = null
        val failing = object : Statement() {
            override fun evaluate() {
                during = AppGraph.newTask
                throw AssertionError("test failed")
            }
        }
        try {
            rule.apply(failing, Description.EMPTY).evaluate()
            fail("the test's failure must propagate")
        } catch (expected: AssertionError) {
            assertEquals("test failed", expected.message)
        }
        assertSame(rule.controller, during)
        assertNotSame(appController, during)
        assertSame(appController, AppGraph.newTask)
    }
}
