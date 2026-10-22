package io.github.veganmarshmallow.taskboard

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.setup.SetupActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Captures the PNGs embedded in the PR. Each test also checks the view shows what the picture claims. */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun capture(name: String, state: WidgetState, widthDp: Int = 340, heightDp: Int = 300): View {
        lateinit var widget: View
        instrumentation.runOnMainSync {
            val rendered = WidgetHost(context).render(state, widthDp, heightDp)
            widget = rendered.widget
            Screenshots.save(context, name, WidgetHost.toBitmap(rendered.root))
        }
        return widget
    }

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun View.visible(id: Int) = findViewById<View>(id).visibility == View.VISIBLE

    @Test fun setupScreen() {
        TokenStore(context).clear()
        val intent = Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity: Activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        var bitmap: Bitmap? = null
        instrumentation.runOnMainSync {
            val root = activity.window.decorView
            assertTrue(root.findViewById<View>(R.id.test).isShown)
            bitmap = WidgetHost.toBitmap(root)
        }
        Screenshots.save(context, "1-setup", bitmap!!)
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test fun newTaskForm() {
        val intent = Intent(context, io.github.veganmarshmallow.taskboard.newtask.NewTaskActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        io.github.veganmarshmallow.taskboard.newtask.NewTaskController.session.draft =
            io.github.veganmarshmallow.taskboard.core.task.TaskDraft(
                title = "Weekly digest email of finished tasks",
                goal = "Every Sunday, email a summary of tasks done that week.\n- Group by repo\n- Link each PR",
                doneWhen = "A test email arrives with last week's tasks",
                repo = "VeganMarshmallow/digest",
                approveNow = true,
            )
        val activity: Activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        var bitmap: Bitmap? = null
        instrumentation.runOnMainSync {
            val root = activity.window.decorView
            assertTrue(root.findViewById<View>(R.id.task_submit).isShown)
            bitmap = WidgetHost.toBitmap(root)
        }
        Screenshots.save(context, "9-new-task-form", bitmap!!)
        instrumentation.runOnMainSync { activity.finish() }
        io.github.veganmarshmallow.taskboard.newtask.NewTaskController.session.draft =
            io.github.veganmarshmallow.taskboard.core.task.TaskDraft.EMPTY
    }

    @Test fun widgetWithBoard() {
        val widget = capture("2-widget-board", WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW), heightDp = 470)
        assertTrue(widget.visible(R.id.board_list))
        assertTrue(!widget.visible(R.id.error))
    }

    @Test fun widgetErrorNoToken() {
        val widget = capture("3-widget-error-no-token", WidgetState(null, BoardError.NoToken, FakeBoards.NOW))
        assertTrue(widget.visible(R.id.error))
        assertTrue(widget.text(R.id.error).endsWith("tap to set up"))
        assertTrue(!widget.visible(R.id.board_list))
    }

    @Test fun widgetStaleBoardUnderRateLimit() {
        val state = WidgetState(FakeBoards.realistic(), BoardError.RateLimited(FakeBoards.NOW.plusSeconds(1500)), FakeBoards.NOW)
        val widget = capture("5-widget-stale-rate-limited", state)
        assertTrue(widget.visible(R.id.error))
        assertTrue(widget.visible(R.id.board_list))
    }

    @Test fun widgetEmptyBoard() {
        val widget = capture("4-widget-empty", WidgetState(FakeBoards.emptyWithDone(), null, FakeBoards.NOW), heightDp = 200)
        assertTrue(widget.visible(R.id.board_list))
        assertEquals(View.GONE, widget.findViewById<View>(R.id.error).visibility)
    }
}
