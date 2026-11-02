package io.github.veganmarshmallow.taskboard

import android.app.job.JobScheduler
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.work.RefreshTracker
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.data.TestOverrides
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.preview.TaskPreviewActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Task #44: replying from the real preview, against a local stand-in for GitHub. */
@RunWith(AndroidJUnit4::class)
class ReplyFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var server: FakeGitHubServer? = null
    private val board = "/repos/VeganMarshmallow/claude-workspace"

    @Before @After fun reset() {
        server?.close()
        server = null
        context.getSystemService(JobScheduler::class.java).cancelAll()
        TestOverrides.httpClient = null
        TokenStore(context).clear()
        StateStore(context).save(WidgetState.INITIAL)
    }

    private fun serve(): FakeGitHubServer {
        val s = FakeGitHubServer { method, path ->
            when {
                method == "GET" && path == "$board/issues/12" -> FakeGitHubServer.Answer(200, """{"number":12,"title":"Which charting library?","state":"open","labels":[{"name":"status: blocked"},{"name":"needs-owner"}],"body":"### Repo\n\nVeganMarshmallow/portfolio-site"}""")
                method == "GET" && path.startsWith("$board/issues/12/comments") -> FakeGitHubServer.Answer(200, """[{"id":1,"body":"⛔ Blocked: Chart.js or ECharts?","created_at":"2026-10-23T10:00:00Z"}]""")
                method == "POST" && path == "$board/issues/12/comments" -> FakeGitHubServer.Answer(201, """{"id":2}""")
                method == "POST" && path == "$board/issues/12/labels" -> FakeGitHubServer.Answer(200, "[]")
                method == "DELETE" && path.startsWith("$board/issues/12/labels/") -> FakeGitHubServer.Answer(200, "[]")
                method == "GET" && path.startsWith("$board/issues?") -> FakeGitHubServer.Answer(200, "[]") // the refresh afterwards
                else -> null
            }
        }
        server = s
        TestOverrides.httpClient = s::client
        TokenStore(context).save("github_pat_TESTONLY")
        StateStore(context).save(FakeMerges.boardState())
        return s
    }

    private fun waitUntil(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var ok = false
            instrumentation.runOnMainSync { ok = condition() }
            if (ok) return
            SystemClock.sleep(100)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun launch(): TaskPreviewActivity {
        val monitor = instrumentation.addMonitor(TaskPreviewActivity::class.java.name, null, false)
        try {
            context.startActivity(TaskPreviewActivity.intent(context, 12))
            return (monitor.waitForActivityWithTimeout(10_000) as TaskPreviewActivity?).also { assertNotNull("activity launched", it) }!!
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun doubleTapSendPostsOnceAndUnblocks() {
        val s = serve()
        val activity = launch()
        try {
            waitUntil("reply box with the unblock checkbox") {
                activity.findViewById<View>(R.id.preview_reply).isShown &&
                    activity.findViewById<CheckBox>(R.id.preview_reply_unblock).let { it.isShown && it.isChecked }
            }
            instrumentation.runOnMainSync {
                activity.findViewById<EditText>(R.id.preview_reply_input).setText("ECharts, כי צריך heatmap")
                val send = activity.findViewById<Button>(R.id.preview_reply_send)
                send.performClick()
                send.performClick() // the second tap arrives before the first send is done
            }
            waitUntil("sent") {
                activity.findViewById<TextView>(R.id.preview_reply_message).text.toString() ==
                    context.getString(R.string.reply_sent_unblocked)
            }
            // Let the widget refresh the send started end here, so it can't leak into later tests.
            waitUntil("the refresh after the reply ended", timeoutMs = 60_000) {
                s.received.any { it.path.startsWith("$board/issues?") } &&
                    AppGraph.refreshTracker(context).status() != RefreshTracker.Status.RUNNING
            }
            val posts = s.received.filter { it.method == "POST" && it.path == "$board/issues/12/comments" }
            assertEquals("a double tap posts once", 1, posts.size)
            assertTrue(posts.single().body.contains("ECharts, כי צריך heatmap"))
            assertEquals(1, s.received.count { it.method == "POST" && it.path == "$board/issues/12/labels" })
            assertEquals(2, s.received.count { it.method == "DELETE" })
            instrumentation.runOnMainSync {
                assertEquals("", activity.findViewById<EditText>(R.id.preview_reply_input).text.toString())
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
