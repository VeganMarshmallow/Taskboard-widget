package io.github.veganmarshmallow.taskboard

import android.app.Activity
import android.content.Intent
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.HttpClient
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import io.github.veganmarshmallow.taskboard.core.json.JsonWriter
import io.github.veganmarshmallow.taskboard.core.task.SubmitMarker
import io.github.veganmarshmallow.taskboard.core.task.SubmitMarkerCodec
import io.github.veganmarshmallow.taskboard.core.task.TaskDraft
import io.github.veganmarshmallow.taskboard.core.task.TaskDraftCodec
import io.github.veganmarshmallow.taskboard.data.NewTaskPrefs
import io.github.veganmarshmallow.taskboard.newtask.NewTaskActivity
import io.github.veganmarshmallow.taskboard.newtask.NewTaskDeps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.CountDownLatch

/**
 * #20 screenshots: the New task form opened after a submit was cut short (process death or a
 * timeout), in each state of the GitHub check. Each test also checks what its picture claims.
 */
@Screenshot
@RunWith(AndroidJUnit4::class)
class NewTaskRecoveryScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val started: Instant = Instant.now().minusSeconds(30)
    private val draft = TaskDraft(
        title = "Weekly digest email of finished tasks",
        goal = "Every Sunday, email a summary of tasks done that week.",
        doneWhen = "A test email arrives with last week's tasks",
        repo = "VeganMarshmallow/digest",
    )

    @Volatile private var lookup: () -> HttpResponse = { HttpResponse(200, body = "[]") }
    private val github = HttpClient { request ->
        if ("since=" in request.url) lookup() else HttpResponse(200, body = "[]") // [] for repo suggestions
    }

    @get:Rule val newTask = NewTaskRule { NewTaskDeps.standard { GitHubApi(github, "github_pat_X") } }

    private fun leftByDeadSubmit() {
        val prefs = NewTaskPrefs(context)
        prefs.draft = TaskDraftCodec.encode(draft)
        prefs.marker = SubmitMarkerCodec.encode(SubmitMarker(draft.title, started))
    }

    private fun launch(): Activity {
        val intent = Intent(context, NewTaskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return instrumentation.startActivitySync(intent).also { instrumentation.waitForIdleSync() }
    }

    private fun Activity.text(id: Int): String = findViewById<TextView>(id).text.toString()

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            assertTrue("timed out: $what", System.currentTimeMillis() < deadline)
            Thread.sleep(50)
            instrumentation.waitForIdleSync()
        }
    }

    private fun Activity.capture(name: String) = ScreenshotCapture.activity(name, this)

    private fun Activity.close() = instrumentation.runOnMainSync { finish() }

    @Test fun checkingGitHub() {
        leftByDeadSubmit()
        val gate = CountDownLatch(1)
        lookup = { gate.await(); HttpResponse(200, body = "[]") }
        val activity = launch()
        try {
            assertTrue("Checking GitHub" in activity.text(R.id.task_status))
            assertFalse(activity.findViewById<Button>(R.id.task_submit).isEnabled)
            activity.capture("20-1-checking-github")
        } finally {
            gate.countDown()
        }
        waitUntil("check finished") { activity.findViewById<Button>(R.id.task_submit).isEnabled }
        activity.close()
    }

    @Test fun createdBeforeTheAppClosed() {
        leftByDeadSubmit()
        val issue = mapOf("number" to 91L, "title" to draft.title, "created_at" to started.plusSeconds(1).toString())
        lookup = { HttpResponse(200, body = JsonWriter.write(listOf(issue))) }
        val activity = launch()
        waitUntil("created shown") { "#91" in activity.text(R.id.task_status) }
        assertEquals("", activity.text(R.id.task_title))
        activity.capture("20-2-created")
        activity.close()
    }

    @Test fun notCreatedOffersTheDraftAgain() {
        leftByDeadSubmit()
        val activity = launch()
        waitUntil("not created shown") { "isn't on GitHub" in activity.text(R.id.task_status) }
        assertEquals(draft.title, activity.text(R.id.task_title))
        assertTrue(activity.findViewById<Button>(R.id.task_submit).isEnabled)
        activity.capture("20-3-not-created-try-again")
        activity.close()
    }

    @Test fun checkFailedSaysCheckTheBoard() {
        leftByDeadSubmit()
        lookup = { HttpResponse(401, body = """{"message":"Bad credentials"}""") }
        val activity = launch()
        waitUntil("check failed shown") { "Check the board" in activity.text(R.id.task_status) }
        assertEquals(draft.title, activity.text(R.id.task_title))
        activity.capture("20-4-check-failed")
        activity.close()
    }
}
