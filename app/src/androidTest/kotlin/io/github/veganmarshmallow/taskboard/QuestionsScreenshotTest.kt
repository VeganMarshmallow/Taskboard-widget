package io.github.veganmarshmallow.taskboard

import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.TabTestViews.assertHeaderFits
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.questions.QuestionsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Task #82: the Questions screen and the widget header's badge, against [FakeQuestions]. */
@Screenshot
@RunWith(AndroidJUnit4::class)
class QuestionsScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var server: FakeGitHubServer? = null

    @Before @After fun reset() {
        FakeQuestions.reset(server)
        server = null
    }

    @Test fun questionsList() {
        server = FakeQuestions.serve()
        val activity = FakeQuestions.launch(QuestionsActivity::class.java, QuestionsActivity.intent(context))
        try {
            FakeQuestions.waitUntil("both lists loaded") { FakeQuestions.loaded(activity) }
            ScreenshotCapture.activity("82-1-questions-list", activity) { root ->
                val answered = root.findViewById<android.view.ViewGroup>(R.id.questions_to_claude).getChildAt(1)
                assertEquals(View.GONE, answered.findViewById<View>(R.id.question_waiting).visibility)
                val waiting = root.findViewById<android.view.ViewGroup>(R.id.questions_to_claude).getChildAt(0)
                assertEquals(View.VISIBLE, waiting.findViewById<View>(R.id.question_waiting).visibility)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun askClaudeAboutATask() {
        server = FakeQuestions.serve()
        val activity = FakeQuestions.launch(QuestionsActivity::class.java, QuestionsActivity.intent(context, 17, "Weekly digest email of finished tasks"))
        try {
            FakeQuestions.waitUntil("loaded") { FakeQuestions.loaded(activity) }
            instrumentation.runOnMainSync {
                FakeQuestions.askBox(activity).findViewById<EditText>(R.id.preview_reply_input).setText("Can it skip weeks with nothing done?")
            }
            ScreenshotCapture.activity("82-2-ask-claude", activity) { root ->
                assertTrue(root.findViewById<View>(R.id.questions_ask_general).isShown)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun headerBadge() {
        val widget = ScreenshotCapture.widget("82-3-header-badge", FakeMerges.boardState())
        val badge = widget.findViewById<TextView>(R.id.questions)
        assertEquals("1", badge.text.toString())
        assertTrue("opens the Questions screen", badge.hasOnClickListeners())
        assertHeaderFits(widget, "badge")
        val empty = ScreenshotCapture.widget("82-4-header-180dp", WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW), widthDp = 180)
        assertHeaderFits(empty, "180dp")
    }
}
