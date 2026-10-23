package io.github.veganmarshmallow.taskboard

import android.content.Intent
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.update.UpdateError
import io.github.veganmarshmallow.taskboard.core.update.UpdateMessages
import io.github.veganmarshmallow.taskboard.core.update.UpdatePresenter
import io.github.veganmarshmallow.taskboard.core.update.UpdatePresenter.Activity
import io.github.veganmarshmallow.taskboard.core.update.UpdateStatus
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.setup.SetupActivity
import io.github.veganmarshmallow.taskboard.update.InstalledVersion
import io.github.veganmarshmallow.taskboard.update.UpdateCard
import io.github.veganmarshmallow.taskboard.update.UpdateStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screenshots for the update check (issue #14; since #32 Update opens the release page in the
 * browser instead of installing). Each also checks the view shows what the picture claims.
 */
@RunWith(AndroidJUnit4::class)
class UpdateScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val presenter = UpdatePresenter(UpdateMessages { "17:05" })

    @After fun cleanUp() {
        UpdateStore(context).save(UpdateStatus.NEVER)
    }

    /** Inflates the real update card, binds [activity] for [status], and saves it as [name]. */
    private fun card(name: String, status: UpdateStatus, activity: Activity): View {
        lateinit var root: View
        instrumentation.runOnMainSync {
            val density = context.resources.displayMetrics.density
            // The app's theme, as in SetupActivity: without it the card renders in the platform's blue accent.
            val themed = android.view.ContextThemeWrapper(context, R.style.Theme_Taskboard)
            val frame = FrameLayout(themed).apply { setBackgroundColor(android.graphics.Color.WHITE) }
            val pad = (16 * density).toInt()
            frame.setPadding(pad, 0, pad, pad)
            root = LayoutInflater.from(themed).inflate(R.layout.section_update, frame, false)
            frame.addView(root)
            UpdateCard(root).bind(presenter.present(status, InstalledVersion.of(context), activity))
            val w = (392 * density).toInt()
            frame.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            frame.layout(0, 0, w, frame.measuredHeight)
            Screenshots.save(context, name, WidgetHost.toBitmap(frame))
        }
        return root
    }

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun View.visible(id: Int) = findViewById<View>(id).visibility == View.VISIBLE

    @Test fun setupScreenScrolledToUpdateAvailable() {
        TokenStore(context).clear() // no token: the app doesn't check on open, so the seeded status stays
        UpdateStore(context).save(FakeUpdates.available())
        val intent = Intent(context, SetupActivity::class.java)
            .setAction(SetupActivity.ACTION_SHOW_UPDATES).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        Thread.sleep(300) // let the posted scroll run
        instrumentation.waitForIdleSync()
        var bitmap: Bitmap? = null
        instrumentation.runOnMainSync {
            val root = activity.window.decorView
            assertEquals("Update available (v0.1.57): tap Update to download it in your browser", root.text(R.id.update_status))
            assertTrue(root.visible(R.id.update_install))
            val instructions = root.findViewById<TextView>(R.id.instructions).text.toString()
            assertTrue(instructions.contains("Contents: Read-only"))
            assertTrue(instructions.contains("downloaded in your browser"))
            bitmap = WidgetHost.toBitmap(root)
        }
        Screenshots.save(context, "14-1-setup-update-available", bitmap!!)
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test fun updateAvailableCard() {
        val v = card("14-2-update-available", FakeUpdates.available(), Activity.Idle)
        assertEquals("Update available (v0.1.57): tap Update to download it in your browser", v.text(R.id.update_status))
        assertTrue(v.visible(R.id.update_install))
        assertTrue(v.findViewById<View>(R.id.update_install).isEnabled)
    }

    @Test fun checkingDisablesTheButtons() {
        val v = card("14-7-checking", FakeUpdates.available(), Activity.Checking)
        assertEquals("Checking for updates…", v.text(R.id.update_status))
        assertTrue(!v.visible(R.id.update_install))
        assertTrue(!v.findViewById<View>(R.id.update_check).isEnabled)
    }

    @Test fun missingTokenAccess() {
        val v = card("14-3-error-no-repo-access", UpdateStatus(FakeBoards.NOW, null, UpdateError.NoRepoAccess), Activity.Idle)
        assertTrue(v.text(R.id.update_status).contains("Contents to Read-only"))
        assertTrue(!v.visible(R.id.update_install))
    }

    @Test fun noBrowserKeepsTheUpdateButton() {
        val v = card("14-4-error-no-browser", FakeUpdates.available(), Activity.Failed(UpdateError.Unexpected("no browser to open the release page")))
        assertTrue(v.text(R.id.update_status).contains("no browser"))
        assertTrue(v.visible(R.id.update_install))
    }

    @Test fun upToDate() {
        val installed = InstalledVersion.of(context)
        val v = card("14-5-up-to-date", UpdateStatus(FakeBoards.NOW, FakeUpdates.V57.copy(version = FakeUpdates.V57.version.copy(versionCode = installed.versionCode))), Activity.Idle)
        assertTrue(v.text(R.id.update_status).startsWith("Up to date"))
    }

    @Test fun widgetWithUpdateDot() {
        UpdateStore(context).save(FakeUpdates.available())
        lateinit var widget: View
        instrumentation.runOnMainSync {
            val rendered = WidgetHost(context).render(WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW), 340, 300)
            widget = rendered.widget
            Screenshots.save(context, "14-6-widget-update-dot", WidgetHost.toBitmap(rendered.root))
        }
        assertTrue(widget.visible(R.id.update_dot))
    }
}
