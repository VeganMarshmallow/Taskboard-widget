package io.github.veganmarshmallow.taskboard

import android.app.Activity
import android.app.Notification
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.automerge.AutoMergeJob
import io.github.veganmarshmallow.taskboard.core.automerge.AutoMergeOutcome
import io.github.veganmarshmallow.taskboard.core.merge.PullRef
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStop
import io.github.veganmarshmallow.taskboard.core.mergeall.PlannedMerge
import io.github.veganmarshmallow.taskboard.data.AutoMergePrefs
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.mergeall.MergeNotices
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #79 for the owner: the settings screen's Merging section with the Auto merge switch off (the
 * default) and on, and the notifications an auto merge posts (a merge, and a failure). Names are
 * `79-<n>-<what>`.
 */
@Screenshot
@RunWith(AndroidJUnit4::class)
class AutoMergeScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = AutoMergePrefs(context)

    @After fun off() {
        prefs.setEnabled(false)
    }

    @Test fun settingsSwitchOffByDefaultThenOn() {
        TokenStore(context).clear() // no token: the screen doesn't check for updates on open
        context.getSharedPreferences("auto_merge", 0).edit().clear().commit()
        settings("79-1-settings-auto-merge-off", on = false)
        prefs.setEnabled(true)
        settings("79-2-settings-auto-merge-on", on = true)
    }

    @Test fun notificationsForAMergeAndAFailure() {
        val merge = PlannedMerge(79, "Auto merge setting", PullRef(FakeMerges.WIDGET, 56))
        notification("79-3-notification-merged", AutoMergeOutcome.Merged(merge))
        notification("79-4-notification-failed", AutoMergeOutcome.Failed(merge, MergeAllStop(MergeAllStop.Kind.CHECKS_FAILED, "emulator")))
    }

    /** Opens settings, scrolls to the Merging section, checks the switch shows [on], saves [name]. */
    private fun settings(name: String, on: Boolean) {
        val intent = Intent(context, io.github.veganmarshmallow.taskboard.setup.SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity: Activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        ScreenshotCapture.activity(name, activity) { root ->
            val toggle = root.findViewById<Switch>(R.id.auto_merge_switch)
            assertEquals(on, toggle.isChecked)
            val scroll = root.findViewById<ScrollView>(R.id.setup_scroll)
            val section = root.findViewById<View>(R.id.auto_merge_section)
            val at = Rect().also { section.getDrawingRect(it) }
            scroll.offsetDescendantRectToMyCoords(section, at)
            scroll.scrollTo(0, at.top - (160 * root.resources.displayMetrics.density).toInt())
            assertTrue("switch on screen", toggle.getGlobalVisibleRect(Rect()))
        }
        instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
    }

    /** Renders the expanded notification [outcome] posts (the system's own template) and saves it as [name]. */
    @Suppress("DEPRECATION")
    private fun notification(name: String, outcome: AutoMergeOutcome) {
        val notice = AutoMergeJob.notice(context, outcome)
        assertNotNull(notice)
        assertTrue("tapping it opens the PR", notice!!.url!!.endsWith("/pull/56"))
        var frame: FrameLayout? = null
        instrumentation.runOnMainSync {
            val themed = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Light)
            val built = MergeNotices.build(context, notice)
            val views = runCatching { Notification.Builder.recoverBuilder(context, built).createBigContentView() }.getOrNull() ?: return@runOnMainSync
            val f = FrameLayout(themed).apply {
                setBackgroundColor(Color.WHITE)
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            f.addView(views.apply(themed, f))
            val width = (380 * f.resources.displayMetrics.density).toInt()
            f.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            f.layout(0, 0, width, f.measuredHeight)
            frame = f
        }
        val rendered = frame
        assumeTrue("this system can't render its notification template off-screen", rendered != null && rendered.height > 0)
        assertFalse(notice.title.isBlank())
        instrumentation.runOnMainSync { Screenshots.save(context, name, WidgetHost.toBitmap(rendered!!)) }
    }
}
