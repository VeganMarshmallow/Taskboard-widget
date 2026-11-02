package io.github.veganmarshmallow.taskboard

import android.app.job.JobScheduler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.work.RetryBackoff
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.data.TestOverrides
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.newtask.NewTaskController
import io.github.veganmarshmallow.taskboard.newtask.NewTaskDeps
import io.github.veganmarshmallow.taskboard.preview.PreviewWorker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Task #23: the debug-only [TestOverrides] seams fall back to the app's own pieces when reset, and
 * the preview's Approve (its own thread, no longer BackgroundWork) reports on the main thread and
 * still finishes after the preview closes.
 */
@RunWith(AndroidJUnit4::class)
class AppGraphSeamsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var server: FakeGitHubServer? = null

    @Before @After fun reset() {
        server?.close()
        server = null
        context.getSystemService(JobScheduler::class.java).cancelAll()
        TestOverrides.httpClient = null
        TestOverrides.retryBackoff = null
        TestOverrides.newTask = null
        TokenStore(context).clear()
        StateStore(context).save(WidgetState.INITIAL)
    }

    @Test fun resettingTheNewTaskOverrideRestoresTheAppsController() {
        val app = AppGraph.newTask
        assertSame("one controller per process", app, AppGraph.newTask)
        val fake = NewTaskController(NewTaskDeps.standard { null })
        TestOverrides.newTask = fake
        assertSame(fake, AppGraph.newTask)
        TestOverrides.newTask = null
        assertSame(app, AppGraph.newTask)
    }

    @Test fun thePlannerIsKeptUntilTheBackoffOverrideChanges() {
        val default = AppGraph.retryPlanner(context)
        assertSame("no override: the same planner every time", default, AppGraph.retryPlanner(context))
        TestOverrides.retryBackoff = RetryBackoff(listOf(Duration.ofSeconds(1)), Duration.ofSeconds(1))
        val quick = AppGraph.retryPlanner(context)
        assertNotSame(default, quick)
        assertSame(quick, AppGraph.retryPlanner(context))
        TestOverrides.retryBackoff = null
        val back = AppGraph.retryPlanner(context)
        assertNotSame("reset rebuilds with the default backoff", quick, back)
        assertSame(back, AppGraph.retryPlanner(context))
    }

    @Test fun approveWithoutATokenReportsNoTokenOnTheMainThread() {
        val (result, onMain) = approveAndWait(PreviewWorker(context))
        assertEquals(Outcome.Failure(BoardError.NoToken), result)
        assertTrue("result delivered on the main thread", onMain)
    }

    @Test fun approveReachesTheServerAndReportsSuccessOnTheMainThread() {
        val s = serveApprove()
        val (result, onMain) = approveAndWait(PreviewWorker(context))
        assertTrue("approve succeeded: $result", result is Outcome.Success)
        assertTrue("result delivered on the main thread", onMain)
        assertTrue(s.received.any { it.method == "POST" && it.path.endsWith("/issues/7/labels") })
    }

    @Test fun anApprovalFinishesAfterThePreviewClosesButItsResultIsDropped() {
        val s = serveApprove()
        val worker = PreviewWorker(context)
        val delivered = CountDownLatch(1)
        instrumentation.runOnMainSync {
            worker.approve(7) { delivered.countDown() }
            worker.close()
        }
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (s.received.none { it.method == "POST" } && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("the approval still reached GitHub", s.received.any { it.method == "POST" && it.path.endsWith("/issues/7/labels") })
        instrumentation.waitForIdleSync()
        assertTrue("a closed preview gets no callback", !delivered.await(1, TimeUnit.SECONDS))
    }

    private fun serveApprove(): FakeGitHubServer {
        val s = FakeGitHubServer { method, path ->
            if (method == "POST" && path.endsWith("/labels")) FakeGitHubServer.Answer(200, "[]") else null
        }
        server = s
        TestOverrides.httpClient = s::client
        TokenStore(context).save("github_pat_TESTONLY")
        return s
    }

    private fun approveAndWait(worker: PreviewWorker): Pair<Outcome<Unit>?, Boolean> {
        val result = AtomicReference<Outcome<Unit>?>()
        val onMain = AtomicReference(false)
        val done = CountDownLatch(1)
        worker.approve(7) {
            onMain.set(Looper.myLooper() == Looper.getMainLooper())
            result.set(it)
            done.countDown()
        }
        assertTrue("approve answered within its budget", done.await(40, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { worker.close() }
        return result.get() to onMain.get()
    }
}
