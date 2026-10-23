package io.github.veganmarshmallow.taskboard

import android.content.Intent
import android.content.pm.PackageManager
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.update.UpdateError
import io.github.veganmarshmallow.taskboard.core.update.UpdateStatus
import io.github.veganmarshmallow.taskboard.update.InstalledVersion
import io.github.veganmarshmallow.taskboard.update.ReleasePageIntent
import io.github.veganmarshmallow.taskboard.update.UpdateBadge
import io.github.veganmarshmallow.taskboard.update.UpdateStore
import io.github.veganmarshmallow.taskboard.widget.WidgetViews
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Android side of the update check: the cache, the widget dot, and the release-page intent. */
@RunWith(AndroidJUnit4::class)
class UpdateAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @After fun cleanUp() {
        UpdateStore(context).save(UpdateStatus.NEVER)
    }

    @Test fun statusSurvivesANewStoreInstance() {
        val status = UpdateStatus(FakeBoards.NOW, FakeUpdates.V57, UpdateError.RateLimited(FakeBoards.NOW))
        UpdateStore(context).save(status)
        assertEquals(status, UpdateStore(context).load())
    }

    @Test fun installedVersionIsReadable() {
        val installed = InstalledVersion.of(context)
        assertTrue(installed.versionCode >= 1)
        assertTrue(installed.versionName.isNotBlank())
    }

    @Test fun badgeFollowsTheCachedCheck() {
        assertFalse(UpdateBadge.isShown(context))
        UpdateStore(context).save(FakeUpdates.available())
        assertTrue(UpdateBadge.isShown(context))
        val current = FakeUpdates.V57.copy(version = FakeUpdates.V57.version.copy(versionCode = InstalledVersion.of(context).versionCode))
        UpdateStore(context).save(UpdateStatus(FakeBoards.NOW, current))
        assertFalse(UpdateBadge.isShown(context))
    }

    @Test fun widgetHeaderShowsTheDotOnlyWhenAnUpdateIsAvailable() {
        fun dot(): Int {
            var v = 0
            instrumentation.runOnMainSync {
                v = WidgetViews(context).build(WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW), 1)
                    .apply(context, FrameLayout(context)).findViewById<View>(R.id.update_dot).visibility
            }
            return v
        }
        assertEquals(View.GONE, dot())
        UpdateStore(context).save(FakeUpdates.available())
        assertEquals(View.VISIBLE, dot())
    }

    @Test fun releasePageIntentOpensTheBrowser() {
        val intent = ReleasePageIntent.of(FakeUpdates.PAGE)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(FakeUpdates.PAGE, intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals(null, intent.type)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Play Protect flagged the in-app installer (#32): the app must not ask to install packages or serve files. */
    @Test fun manifestHasNoInstallPermissionAndNoProvider() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_PROVIDERS)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertTrue(info.providers.isNullOrEmpty())
    }
}
