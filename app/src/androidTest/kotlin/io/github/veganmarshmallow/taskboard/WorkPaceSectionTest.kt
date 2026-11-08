package io.github.veganmarshmallow.taskboard

import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.pace.WorkPace
import io.github.veganmarshmallow.taskboard.core.pace.WorkPaceForm.Phase
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.data.WorkPaceStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Work pace section (#83) against a fake board repo: no file reads as Save usage and the first
 * save creates it (no sha); a failed read locks the options with a retry; a refused save puts the
 * stored option back and asks for Contents write access.
 */
@RunWith(AndroidJUnit4::class)
class WorkPaceSectionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var fake: FakeBoardSettings? = null

    @After fun reset() {
        fake?.close()
        TokenStore(context).clear()
        WorkPaceStore(context).remember(WorkPace.SAVE)
    }

    private fun serve(text: String?) = FakeBoardSettings(text).install().also { fake = it }

    @Test fun noFileIsSaveUsageAndTheFirstSaveCreatesIt() {
        val f = serve(null)
        val activity = FakeBoardSettings.openSettings { it.phase == Phase.READY }
        assertTrue(activity.findViewById<RadioButton>(R.id.pace_save).isChecked)
        instrumentation.runOnMainSync { activity.findViewById<RadioButton>(R.id.pace_fast).performClick() }
        FakeBoardSettings.awaitForm(activity) { it.phase == Phase.SAVED }
        assertFalse("a new file has no sha", f.puts.single().contains("\"sha\""))
        assertEquals("""{"pace":"fast"}""" + "\n", f.text)
        FakeBoardSettings.finish(activity)
    }

    @Test fun aStoredFasterIsShownAndRemembered() {
        serve("""{"pace":"fast"}""")
        val activity = FakeBoardSettings.openSettings { it.phase == Phase.READY }
        assertTrue(activity.findViewById<RadioButton>(R.id.pace_fast).isChecked)
        assertEquals(WorkPace.FAST, WorkPaceStore(context).cached())
        FakeBoardSettings.finish(activity)
    }

    @Test fun aFailedReadLocksTheOptionsUntilRetried() {
        val f = serve("""{"pace":"fast"}""").apply { getStatus = 500 }
        val activity = FakeBoardSettings.openSettings { it.phase == Phase.LOAD_FAILED }
        instrumentation.runOnMainSync {
            assertFalse(activity.findViewById<RadioButton>(R.id.pace_fast).isEnabled)
            assertEquals(View.VISIBLE, activity.findViewById<Button>(R.id.work_pace_retry).visibility)
        }
        f.getStatus = 200
        instrumentation.runOnMainSync { activity.findViewById<Button>(R.id.work_pace_retry).performClick() }
        FakeBoardSettings.awaitForm(activity) { it.phase == Phase.READY }
        instrumentation.runOnMainSync {
            assertTrue(activity.findViewById<RadioButton>(R.id.pace_fast).isChecked)
            assertEquals(View.GONE, activity.findViewById<Button>(R.id.work_pace_retry).visibility)
        }
        FakeBoardSettings.finish(activity)
    }

    @Test fun aRefusedSavePutsTheStoredOptionBack() {
        val f = serve("""{"pace":"save"}""").apply { putStatus = 403 }
        val activity = FakeBoardSettings.openSettings { it.phase == Phase.READY }
        instrumentation.runOnMainSync { activity.findViewById<RadioButton>(R.id.pace_fast).performClick() }
        FakeBoardSettings.awaitForm(activity) { it.phase == Phase.SAVE_FAILED }
        instrumentation.runOnMainSync {
            assertTrue(activity.findViewById<RadioButton>(R.id.pace_save).isChecked)
            assertTrue(activity.findViewById<TextView>(R.id.work_pace_status).text.toString().contains("Contents: Read and write"))
        }
        assertEquals("""{"pace":"save"}""", f.text)
        assertEquals(WorkPace.SAVE, WorkPaceStore(context).cached())
        FakeBoardSettings.finish(activity)
    }
}
