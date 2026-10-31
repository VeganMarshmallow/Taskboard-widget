package io.github.veganmarshmallow.taskboard

import android.app.Activity
import android.content.Intent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.task.TaskDraft
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.NewTaskPrefs
import io.github.veganmarshmallow.taskboard.newtask.NewTaskActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** #9: the new-task form, filled in. */
@Screenshot
@RunWith(AndroidJUnit4::class)
class NewTaskScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun newTaskForm() {
        val intent = Intent(context, NewTaskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        AppGraph.newTask.session.draft = TaskDraft(
            title = "Weekly digest email of finished tasks",
            goal = "Every Sunday, email a summary of tasks done that week.\n- Group by repo\n- Link each PR",
            doneWhen = "A test email arrives with last week's tasks",
            repo = "VeganMarshmallow/digest",
            approveNow = true,
        )
        val activity: Activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        ScreenshotCapture.activity("9-new-task-form", activity) { root -> assertTrue(root.findViewById<View>(R.id.task_submit).isShown) }
        instrumentation.runOnMainSync { activity.finish() }
        AppGraph.newTask.session.draft = TaskDraft.EMPTY
        NewTaskPrefs(context).draft = null // saved on pause (#20)
    }
}
