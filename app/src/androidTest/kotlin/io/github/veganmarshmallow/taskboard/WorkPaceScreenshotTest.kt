package io.github.veganmarshmallow.taskboard

import android.graphics.Rect
import android.view.View
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.pace.WorkPace
import io.github.veganmarshmallow.taskboard.core.pace.WorkPaceForm.Phase
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.data.WorkPaceStore
import io.github.veganmarshmallow.taskboard.setup.SetupActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #83 for the owner: the settings screen's Work pace section read from a fake board repo (Save usage),
 * then after tapping Faster and saving it. Names are `83-<n>-<what>`.
 */
@Screenshot
@RunWith(AndroidJUnit4::class)
class WorkPaceScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fake = FakeBoardSettings("""{"pace":"save","theme":"ink"}""").install()

    @After fun reset() {
        fake.close()
        TokenStore(context).clear()
        WorkPaceStore(context).remember(WorkPace.SAVE)
    }

    @Test fun readThenSwitchedToFaster() {
        WorkPaceStore(context).remember(WorkPace.SAVE)
        val activity = FakeBoardSettings.openSettings { it.phase == Phase.READY }
        capture("83-1-work-pace-save", activity) { root ->
            assertTrue(root.findViewById<RadioButton>(R.id.pace_save).isChecked)
            assertTrue(root.findViewById<RadioButton>(R.id.pace_fast).isEnabled)
        }
        instrumentation.runOnMainSync { activity.findViewById<RadioButton>(R.id.pace_fast).performClick() }
        FakeBoardSettings.awaitForm(activity) { it.phase == Phase.SAVED }
        capture("83-2-work-pace-fast", activity) { root ->
            assertTrue(root.findViewById<RadioButton>(R.id.pace_fast).isChecked)
            assertEquals(context.getString(R.string.pace_saved), root.findViewById<TextView>(R.id.work_pace_status).text.toString())
        }
        assertEquals("""{"theme":"ink","pace":"fast"}""" + "\n", fake.text)
        assertEquals(WorkPace.FAST, WorkPaceStore(context).cached())
        FakeBoardSettings.finish(activity)
    }

    /** Scrolls the Work pace section into view, runs [check] on the window, saves it as [name]. */
    private fun capture(name: String, activity: SetupActivity, check: (View) -> Unit) {
        instrumentation.waitForIdleSync()
        ScreenshotCapture.activity(name, activity) { root ->
            val scroll = root.findViewById<ScrollView>(R.id.setup_scroll)
            val section = root.findViewById<View>(R.id.work_pace_section)
            val at = Rect().also { section.getDrawingRect(it) }
            scroll.offsetDescendantRectToMyCoords(section, at)
            scroll.scrollTo(0, at.top - (120 * root.resources.displayMetrics.density).toInt())
            check(root)
            assertTrue("section on screen", root.findViewById<View>(R.id.pace_fast).getGlobalVisibleRect(Rect()))
        }
    }
}
