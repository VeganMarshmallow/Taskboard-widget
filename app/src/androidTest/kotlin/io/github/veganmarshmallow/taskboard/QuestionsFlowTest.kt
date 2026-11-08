package io.github.veganmarshmallow.taskboard

import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.FakeQuestions.BOARD
import io.github.veganmarshmallow.taskboard.questions.QuestionsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Task #82: the Questions screen against a local stand-in for GitHub: list, answer, ask about a task, ask in general. */
@RunWith(AndroidJUnit4::class)
class QuestionsFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var server: FakeGitHubServer? = null

    @Before @After fun reset() {
        FakeQuestions.reset(server)
        server = null
    }

    private fun serve() = FakeQuestions.serve().also { server = it }

    private fun refreshes(s: FakeGitHubServer) = s.received.count { it.method == "GET" && it.path.startsWith("$BOARD/issues?") }

    @Test fun listsQuestionsAndAnswerUnblocksTheTask() {
        val s = serve()
        val activity = FakeQuestions.launch(QuestionsActivity::class.java, QuestionsActivity.intent(context))
        try {
            FakeQuestions.waitUntil("both lists loaded") { FakeQuestions.loaded(activity) }
            val card = FakeQuestions.claudeCard(activity)
            instrumentation.runOnMainSync {
                assertTrue(card.findViewById<TextView>(R.id.question_task).text.toString().startsWith("#12 "))
                assertEquals(
                    "Chart.js (small) or ECharts (has heatmaps)?",
                    card.findViewById<TextView>(R.id.preview_claude_need_text).text.toString(),
                )
                assertTrue("unblock offered and on", card.findViewById<CheckBox>(R.id.preview_reply_unblock).let { it.isShown && it.isChecked })
            }
            val before = refreshes(s)
            instrumentation.runOnMainSync {
                card.findViewById<EditText>(R.id.preview_reply_input).setText("ECharts")
                val send = card.findViewById<Button>(R.id.preview_reply_send)
                send.performClick()
                send.performClick() // a double tap posts once
            }
            FakeQuestions.waitUntil("reply posted and unblocked") {
                s.received.count { it.method == "DELETE" && it.path.startsWith("$BOARD/issues/12/labels/") } == 2
            }
            FakeQuestions.waitForRefresh(s, before)
            val posts = s.received.filter { it.method == "POST" && it.path == "$BOARD/issues/12/comments" }
            assertEquals(1, posts.size)
            assertTrue(posts.single().body.contains("ECharts"))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun askAboutATaskPostsTheQuestionAndItsLabel() {
        val s = serve()
        val activity = FakeQuestions.launch(QuestionsActivity::class.java, QuestionsActivity.intent(context, 17, "Weekly digest email of finished tasks"))
        try {
            FakeQuestions.waitUntil("loaded") { FakeQuestions.loaded(activity) }
            val box = FakeQuestions.askBox(activity)
            instrumentation.runOnMainSync {
                assertEquals("About #17 Weekly digest email of finished tasks", activity.findViewById<TextView>(R.id.questions_ask_topic).text.toString())
                box.findViewById<EditText>(R.id.preview_reply_input).setText("Can it skip weeks with nothing done?")
            }
            val before = refreshes(s)
            instrumentation.runOnMainSync { box.findViewById<Button>(R.id.preview_reply_send).performClick() }
            FakeQuestions.waitUntil("asked") {
                box.findViewById<TextView>(R.id.preview_reply_message).text.toString() == context.getString(R.string.questions_ask_sent)
            }
            FakeQuestions.waitForRefresh(s, before)
            val comment = s.received.single { it.method == "POST" && it.path == "$BOARD/issues/17/comments" }
            assertTrue(comment.body, comment.body.contains("**Question for Claude:**\\n\\nCan it skip weeks with nothing done?"))
            val label = s.received.single { it.method == "POST" && it.path == "$BOARD/issues/17/labels" }
            assertTrue(label.body, label.body.contains("question-for-claude"))
            assertFalse("no general issue", s.received.any { it.method == "POST" && it.path == "$BOARD/issues" })
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun generalQuestionCreatesAnIssueNotReadyForClaude() {
        val s = serve()
        val activity = FakeQuestions.launch(QuestionsActivity::class.java, QuestionsActivity.intent(context, 17, "Weekly digest"))
        try {
            FakeQuestions.waitUntil("loaded") { FakeQuestions.loaded(activity) }
            val box = FakeQuestions.askBox(activity)
            instrumentation.runOnMainSync {
                activity.findViewById<Button>(R.id.questions_ask_general).performClick() // switch to a general question
                assertEquals(context.getString(R.string.questions_ask_general), activity.findViewById<TextView>(R.id.questions_ask_topic).text.toString())
                box.findViewById<EditText>(R.id.preview_reply_input).setText("Which repo should the blog live in?")
            }
            val before = refreshes(s)
            instrumentation.runOnMainSync { box.findViewById<Button>(R.id.preview_reply_send).performClick() }
            FakeQuestions.waitUntil("issue created") { s.received.any { it.method == "POST" && it.path == "$BOARD/issues" } }
            FakeQuestions.waitForRefresh(s, before)
            val created = s.received.single { it.method == "POST" && it.path == "$BOARD/issues" }.body
            assertTrue(created, created.contains("Question for Claude: Which repo should the blog live in?"))
            assertTrue(created, created.contains("question-for-claude") && created.contains("status: todo"))
            assertFalse(created, created.contains("ready-for-claude"))
            assertFalse("nothing posted on #17", s.received.any { it.method == "POST" && it.path.startsWith("$BOARD/issues/17/") })
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
