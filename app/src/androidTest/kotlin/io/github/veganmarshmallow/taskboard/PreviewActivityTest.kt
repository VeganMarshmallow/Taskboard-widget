package io.github.veganmarshmallow.taskboard

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.ComponentName
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.preview.TaskPreviewActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The real preview activity: bad launch extras, no token, and rotation handling (review of #11). */
@RunWith(AndroidJUnit4::class)
class PreviewActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before fun noToken() {
        TokenStore(context).clear() // nothing here may touch the network
    }

    @After fun cleanUp() {
        TokenStore(context).clear()
        StateStore(context).save(WidgetState.INITIAL)
    }

    private fun launch(intent: Intent): Activity {
        val monitor = instrumentation.addMonitor(TaskPreviewActivity::class.java.name, null, false)
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val activity = monitor.waitForActivityWithTimeout(10_000)
            assertNotNull("activity was launched", activity)
            return activity!!
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun previewIntent() = Intent(context, TaskPreviewActivity::class.java)

    @Test fun missingIssueNumberFinishesAtOnce() {
        val activity = launch(previewIntent())
        waitUntil("finished", onMainThread = true) { activity.isFinishing || activity.isDestroyed }
    }

    @Test fun invalidIssueNumbersFinishAtOnce() {
        listOf<Intent.() -> Intent>(
            { putExtra("issue", 0) },
            { putExtra("issue", -3) },
            { putExtra("issue", "12") }, // wrong type: read as the default
            { putExtra("issue", 12L) },
        ).forEach { extra ->
            val activity = launch(previewIntent().extra())
            waitUntil("finished", onMainThread = true) { activity.isFinishing || activity.isDestroyed }
        }
    }

    @Test fun withoutATokenTheSheetOffersSetUp() {
        val activity = launch(TaskPreviewActivity.intent(context, 11))
        try {
            waitUntil("Set up offered", onMainThread = true) {
                val retry = activity.findViewById<Button>(R.id.preview_retry)
                retry?.isShown == true && retry.text.toString() == context.getString(R.string.preview_set_up)
            }
            instrumentation.runOnMainSync {
                assertEquals("Task #11", activity.findViewById<android.widget.TextView>(R.id.preview_title).text.toString())
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun rotationKeepsTheSameInstance() {
        val info: ActivityInfo = context.packageManager.getActivityInfo(
            ComponentName(context, TaskPreviewActivity::class.java), PackageManager.GET_META_DATA,
        )
        val handled = ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_SCREEN_SIZE or
            ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or ActivityInfo.CONFIG_SCREEN_LAYOUT
        assertEquals(handled, info.configChanges and handled)
        // Font scale and dark mode still recreate it, so text sizes and colours follow the setting.
        assertTrue(info.configChanges and ActivityInfo.CONFIG_FONT_SCALE == 0)
        assertTrue(info.configChanges and ActivityInfo.CONFIG_UI_MODE == 0)
    }
}
