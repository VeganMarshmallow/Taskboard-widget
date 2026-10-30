package io.github.veganmarshmallow.taskboard

import android.app.Activity
import android.content.Intent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.HttpClient
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch

/**
 * The New task form after process death (task #20): a fresh controller stands for the new process,
 * and [NewTaskPrefs] holds what the dead one saved. GitHub is a fake that answers the one lookup.
 */
@RunWith(AndroidJUnit4::class)
class NewTaskPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = NewTaskPrefs(context)
    private val started: Instant = Instant.now().minusSeconds(60)
    private val draft = TaskDraft(title = "Ship the widget", goal = "Typed before the kill", repo = "Taskboard-widget")

    private val requests: MutableList<HttpRequest> = Collections.synchronizedList(mutableListOf())
    @Volatile private var issues: List<Map<String, Any?>> = emptyList()
    @Volatile private var post: () -> HttpResponse = { HttpResponse(201, body = """{"number":93,"labels":[]}""") }
    private val github = HttpClient { request ->
        requests += request
        when {
            request.method == "POST" -> post()
            "since=" in request.url -> HttpResponse(200, body = JsonWriter.write(issues))
            else -> HttpResponse(200, body = "[]") // repo suggestions
        }
    }

    @get:Rule val newTask = NewTaskRule { NewTaskDeps.standard { GitHubApi(github, "github_pat_X") } }

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

    private fun leftByDeadSubmit() {
        prefs.draft = TaskDraftCodec.encode(draft)
        prefs.marker = SubmitMarkerCodec.encode(SubmitMarker(draft.title, started))
    }

    private fun lookups() = requests.filter { "since=" in it.url }

    @Test fun draftIsSavedOnPauseAndRestoredInANewProcess() {
        val first = launch()
        instrumentation.runOnMainSync {
            first.findViewById<EditText>(R.id.task_title).setText("Half typed")
            first.finish() // Back: pauses, which saves
        }
        waitUntil("draft saved on pause") { TaskDraftCodec.decode(prefs.draft)?.title == "Half typed" }

        newTask.restart() // a new process: fresh controller, same disk
        val again = launch()
        assertEquals("Half typed", again.text(R.id.task_title))
        assertTrue(lookups().isEmpty())
        instrumentation.runOnMainSync { again.findViewById<Button>(R.id.task_cancel).performClick() }
        waitUntil("Discard forgets the saved draft") { prefs.draft == null }
    }

    @Test fun cutShortSubmitThatReachedGitHubShowsCreated() {
        issues = listOf(
            mapOf("number" to 91L, "title" to draft.title, "created_at" to started.plusSeconds(1).toString()),
        )
        leftByDeadSubmit()
        val activity = launch()
        waitUntil("recovery shown") { "#91" in activity.text(R.id.task_status) }
        assertEquals("", activity.text(R.id.task_title))
        assertTrue(activity.findViewById<Button>(R.id.task_submit).isEnabled)
        assertNull(prefs.marker)
        assertNull("no draft left behind after a create", prefs.draft)
        assertEquals(1, lookups().size)
        assertTrue(requests.none { it.method == "POST" })
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test fun cutShortSubmitThatNeverArrivedOffersRetryWithTheDraft() {
        issues = listOf(mapOf("number" to 5L, "title" to "Something else", "created_at" to started.toString()))
        leftByDeadSubmit()
        val activity = launch()
        waitUntil("recovery shown") { "isn't on GitHub" in activity.text(R.id.task_status) }
        assertEquals(draft.title, activity.text(R.id.task_title))
        assertEquals(draft.goal, activity.text(R.id.task_goal))
        assertTrue(activity.findViewById<Button>(R.id.task_submit).isEnabled)
        assertNull(prefs.marker)
        assertNotNull(prefs.draft)
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test fun timedOutSubmitIsCheckedWhenTheFormOpensAgain() {
        post = { throw SocketTimeoutException("read timed out") } // GitHub may have created it anyway
        val first = launch()
        instrumentation.runOnMainSync {
            first.findViewById<EditText>(R.id.task_title).setText(draft.title)
            first.findViewById<Button>(R.id.task_submit).performClick()
        }
        waitUntil("timeout shown") { context.getString(R.string.task_may_exist) in first.text(R.id.task_status) }
        assertNotNull("the marker stays: the issue may exist", prefs.marker)
        instrumentation.runOnMainSync { first.finish() }
        instrumentation.waitForIdleSync()

        issues = listOf(mapOf("number" to 92L, "title" to draft.title, "created_at" to Instant.now().toString()))
        val again = launch() // same process: no restart
        waitUntil("recovery shown") { "#92" in again.text(R.id.task_status) }
        assertEquals("", again.text(R.id.task_title))
        assertNull(prefs.marker)
        assertEquals(1, requests.count { it.method == "POST" })
        instrumentation.runOnMainSync { again.finish() }
    }

    @Test fun corruptSavedDraftAndMarkerOpenAnEmptyFormWithoutALookup() {
        prefs.draft = "{not json"
        prefs.marker = "]["
        newTask.restart()
        val activity = launch()
        assertEquals("", activity.text(R.id.task_title))
        assertTrue(activity.findViewById<Button>(R.id.task_submit).isEnabled)
        assertNull(prefs.marker)
        assertTrue(lookups().isEmpty())
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test fun formStaysDisabledWhileChecking() {
        leftByDeadSubmit()
        val gate = CountDownLatch(1)
        newTask.restart(NewTaskDeps.standard { GitHubApi({ gate.await(); github.execute(it) }, "github_pat_X") })
        val activity = launch()
        try {
            assertTrue("checking", "Checking GitHub" in activity.text(R.id.task_status))
            assertFalse(activity.findViewById<Button>(R.id.task_submit).isEnabled)
        } finally {
            gate.countDown()
        }
        waitUntil("check finished") { activity.findViewById<Button>(R.id.task_submit).isEnabled }
        instrumentation.runOnMainSync { activity.finish() }
    }
}
