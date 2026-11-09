package io.github.veganmarshmallow.taskboard

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.HttpClient
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.task.TaskDraft
import io.github.veganmarshmallow.taskboard.newtask.NewTaskActivity
import io.github.veganmarshmallow.taskboard.newtask.NewTaskDeps
import io.github.veganmarshmallow.taskboard.widget.WidgetViews
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Clock
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The New task form on a device, against a fake GitHub: submit, double tap, failure, rotation. */
@RunWith(AndroidJUnit4::class)
class NewTaskActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** Scripted GitHub: POSTs wait for [release] when set, then get [postResponse]. */
    private class FakeGitHub : HttpClient {
        val posts: MutableList<HttpRequest> = Collections.synchronizedList(mutableListOf())
        @Volatile var release: CountDownLatch? = null
        @Volatile var postResponse: () -> HttpResponse = { HttpResponse(201, body = """{"number":77,"labels":[]}""") }
        @Volatile var getResponse: () -> HttpResponse =
            { HttpResponse(200, body = """[{"number":3,"body":"### Repo\n\nVeganMarshmallow/Taskboard-widget\n"}]""") }

        override fun execute(request: HttpRequest): HttpResponse {
            if (request.method != "POST") return getResponse()
            posts += request
            release?.await(10, TimeUnit.SECONDS)
            return postResponse()
        }
    }

    private val github = FakeGitHub()

    @get:Rule val newTask = NewTaskRule {
        NewTaskDeps.standard { GitHubApi(github, "github_pat_X", Clock.fixed(FakeBoards.NOW, ZoneOffset.UTC)) }
    }

    private fun launch(): Activity {
        val intent = Intent(context, NewTaskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return instrumentation.startActivitySync(intent).also { instrumentation.waitForIdleSync() }
    }

    private fun <T : View> Activity.view(id: Int): T = findViewById(id)

    private fun Activity.fill(title: String, goal: String = "", repo: String = "") = instrumentation.runOnMainSync {
        view<EditText>(R.id.task_title).setText(title)
        view<EditText>(R.id.task_goal).setText(goal)
        view<EditText>(R.id.task_repo).setText(repo)
    }

    private fun Activity.tapSubmit() = instrumentation.runOnMainSync { view<Button>(R.id.task_submit).performClick() }

    @Test fun submitCreatesTheIssueInFormFormatAndCloses() {
        val activity = launch()
        activity.fill("לוח משימות ✅ **bold**", goal = "שורה 1\n### Repo\nשורה 2", repo = "Taskboard-widget")
        instrumentation.runOnMainSync {
            activity.view<Spinner>(R.id.task_type).setSelection(1) // bug
            activity.view<RadioGroup>(R.id.task_priority).check(R.id.priority_high)
            activity.view<CheckBox>(R.id.task_approve).isChecked = true
        }
        activity.tapSubmit()
        waitUntil("form closes") { activity.isFinishing || activity.isDestroyed }

        val json = JsonParser.parse(github.posts.single().body!!) as Map<*, *>
        assertEquals("לוח משימות ✅ **bold**", json["title"])
        assertEquals(listOf("status: todo", "type: bug", "priority: high", "ready-for-claude"), json["labels"])
        val body = json["body"] as String
        assertTrue(body, body.startsWith("### Repo\n\nVeganMarshmallow/Taskboard-widget\n\n### Type\n\nbug\n\n### What should be done\n\nשורה 1\n\\### Repo\nשורה 2"))
        assertTrue(body.endsWith("### Done when\n\n_No response_\n\n### Notes\n\n_No response_"))
        assertEquals(TaskDraft.EMPTY, newTask.controller.session.draft)
    }

    @Test fun doubleTapSendsOneRequestAndDisablesSubmit() {
        github.release = CountDownLatch(1)
        val activity = launch()
        activity.fill("Once only")
        activity.tapSubmit()
        instrumentation.runOnMainSync {
            assertFalse(activity.view<Button>(R.id.task_submit).isEnabled)
            assertFalse(activity.view<EditText>(R.id.task_title).isEnabled)
        }
        activity.tapSubmit()
        activity.tapSubmit()
        waitUntil("request sent") { github.posts.size == 1 }
        github.release!!.countDown()
        waitUntil("form closes") { activity.isFinishing || activity.isDestroyed }
        assertEquals(1, github.posts.size)
    }

    @Test fun failureKeepsEverythingAndShowsTheSpecificError() {
        github.postResponse = { throw IOException("offline") }
        val activity = launch()
        activity.fill("Keep me", goal = "and me", repo = "o/r")
        activity.tapSubmit()
        waitUntil("error shown") { activity.view<TextView>(R.id.task_status).visibility == View.VISIBLE }
        instrumentation.runOnMainSync {
            assertTrue(activity.view<TextView>(R.id.task_status).text.contains("No network"))
            // A dropped connection may still have created the issue: the user is told to check first.
            assertTrue(activity.view<TextView>(R.id.task_status).text.contains(context.getString(R.string.task_may_exist)))
            assertEquals("Keep me", activity.view<EditText>(R.id.task_title).text.toString())
            assertEquals("and me", activity.view<EditText>(R.id.task_goal).text.toString())
            assertEquals("o/r", activity.view<EditText>(R.id.task_repo).text.toString())
            assertTrue(activity.view<Button>(R.id.task_submit).isEnabled)
        }
        assertFalse(activity.isFinishing)

        github.postResponse = { HttpResponse(401, body = "{}") }
        activity.tapSubmit()
        waitUntil("401 shown") { activity.view<TextView>(R.id.task_status).text.contains("401") }
        instrumentation.runOnMainSync {
            assertFalse(activity.view<TextView>(R.id.task_status).text.contains(context.getString(R.string.task_may_exist)))
            activity.finish()
        }
    }

    @Test fun failingSuggestionsDontBlockAndUnapprovedTaskHasNoReadyLabel() {
        github.getResponse = { HttpResponse(500, body = "") }
        github.postResponse = {
            HttpResponse(422, body = """{"message":"Validation Failed","errors":[{"message":"title is too long"}]}""")
        }
        val activity = launch()
        activity.fill("Not approved yet", repo = "https://github.com/VeganMarshmallow/Taskboard-widget/issues/9")
        activity.tapSubmit()
        waitUntil("422 shown") { activity.view<TextView>(R.id.task_status).text.contains("title is too long") }
        instrumentation.runOnMainSync {
            assertFalse(activity.view<TextView>(R.id.task_status).text.contains(context.getString(R.string.task_may_exist)))
            assertFalse(activity.view<CheckBox>(R.id.task_approve).isChecked)
        }
        val json = JsonParser.parse(github.posts.single().body!!) as Map<*, *>
        assertEquals(listOf("status: todo", "type: feature"), json["labels"])
        assertTrue((json["body"] as String).startsWith("### Repo\n\nVeganMarshmallow/Taskboard-widget\n\n### Type\n\nfeature"))
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test fun validationErrorsSendNothing() {
        val activity = launch()
        activity.fill("   ", repo = "a/b/c")
        activity.tapSubmit()
        instrumentation.runOnMainSync {
            assertNotNull(activity.view<EditText>(R.id.task_title).error)
            assertNotNull(activity.view<EditText>(R.id.task_repo).error)
        }
        assertTrue(github.posts.isEmpty())
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test fun draftSurvivesRotation() {
        val activity = launch()
        activity.fill("Rotate me", goal = "גוף")
        instrumentation.runOnMainSync {
            activity.view<CheckBox>(R.id.task_approve).isChecked = true
            activity.view<RadioGroup>(R.id.task_priority).check(R.id.priority_low)
            activity.recreate()
        }
        instrumentation.waitForIdleSync()
        var recreated: Activity? = null
        waitUntil("recreated form resumed") {
            instrumentation.runOnMainSync {
                recreated = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull { it is NewTaskActivity && it !== activity }
            }
            recreated != null
        }
        val again = recreated!!
        instrumentation.runOnMainSync {
            assertEquals("Rotate me", again.view<EditText>(R.id.task_title).text.toString())
            assertEquals("גוף", again.view<EditText>(R.id.task_goal).text.toString())
            assertTrue(again.view<CheckBox>(R.id.task_approve).isChecked)
            assertEquals(R.id.priority_low, again.view<RadioGroup>(R.id.task_priority).checkedRadioButtonId)
            again.finish()
        }
    }

    @Test fun widgetHeaderHasANewTaskButton() {
        lateinit var widget: View
        instrumentation.runOnMainSync {
            widget = WidgetViews(context).build(WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW), 1)
                .apply(context, FrameLayout(context))
        }
        val button = widget.findViewById<View>(R.id.new_task)
        assertEquals(View.VISIBLE, button.visibility)
        assertTrue(button.hasOnClickListeners())
        assertEquals(context.getString(R.string.new_task), button.contentDescription)
    }
}
