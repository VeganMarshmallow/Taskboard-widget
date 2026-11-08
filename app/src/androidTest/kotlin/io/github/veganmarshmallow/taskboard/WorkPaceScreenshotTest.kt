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
import io.github.veganmarshmallow.taskboard.setup.AutoMergeSection
import io.github.veganmarshmallow.taskboard.setup.SetupActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #83 for the owner: the settings screen's Work pace section read from a fake board repo (Save usage),
 * then after tapping Faster and saving it. Names are `83-<n>-<what>`. Both also show the Auto merge
 * description below it, which follows the pace on screen (#88).
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
        capture("83-1-work-pace-save", activity, WorkPace.SAVE) { root ->
            assertTrue(root.findViewById<RadioButton>(R.id.pace_save).isChecked)
            assertTrue(root.findViewById<RadioButton>(R.id.pace_fast).isEnabled)
        }
        instrumentation.runOnMainSync { activity.findViewById<RadioButton>(R.id.pace_fast).performClick() }
        FakeBoardSettings.awaitForm(activity) { it.phase == Phase.SAVED }
        capture("83-2-work-pace-fast", activity, WorkPace.FAST) { root ->
            assertTrue(root.findViewById<RadioButton>(R.id.pace_fast).isChecked)
            assertEquals(context.getString(R.string.pace_saved), root.findViewById<TextView>(R.id.work_pace_status).text.toString())
        }
        assertEquals("other keys kept in place", """{"pace":"fast","theme":"ink"}""" + "\n", fake.text)
        assertEquals(WorkPace.FAST, WorkPaceStore(context).cached())
        FakeBoardSettings.finish(activity)
    }

    /**
     * Scrolls the Work pace section and the Auto merge description below it into view, checks that
     * description reads as [pace]'s, runs [check] on the window, saves it as [name].
     */
    private fun capture(name: String, activity: SetupActivity, pace: WorkPace, check: (View) -> Unit) {
        instrumentation.waitForIdleSync()
        ScreenshotCapture.activity(name, activity) { root ->
            val density = root.resources.displayMetrics.density
            val scroll = root.findViewById<ScrollView>(R.id.setup_scroll)
            val hint = root.findViewById<TextView>(R.id.auto_merge_hint)
            val section = positionIn(scroll, root.findViewById(R.id.work_pace_section))
            val hintAt = positionIn(scroll, hint)
            // the section's top 120dp down, or lower if the Auto merge description would be cut off
            scroll.scrollTo(0, maxOf(section.top - (120 * density).toInt(), hintAt.bottom - scroll.height + (16 * density).toInt()))
            check(root)
            assertTrue("section on screen", root.findViewById<View>(R.id.pace_fast).getGlobalVisibleRect(Rect()))
            assertEquals(autoMergeHint(pace), hint.text.toString())
            val seen = Rect()
            assertTrue("auto merge description on screen", hint.getGlobalVisibleRect(seen))
            assertEquals("auto merge description not cut off", hint.height, seen.height())
        }
    }

    /** [view]'s bounds in [scroll]'s content coordinates. */
    private fun positionIn(scroll: ScrollView, view: View): Rect =
        Rect().also { view.getDrawingRect(it); scroll.offsetDescendantRectToMyCoords(view, it) }

    private fun autoMergeHint(pace: WorkPace): String {
        val expected = context.getString(R.string.auto_merge_hint, context.getString(AutoMergeSection.orderOf(pace)))
        val order = if (pace == WorkPace.FAST) "Ready PRs back to back" else "One at a time"
        assertTrue("says $order", expected.contains("$order, in Merge all's order"))
        return expected
    }
}
