package io.github.veganmarshmallow.taskboard

import android.content.Intent
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.update.UpdateError
import io.github.veganmarshmallow.taskboard.core.update.UpdateStatus
import io.github.veganmarshmallow.taskboard.update.ApkInstaller
import io.github.veganmarshmallow.taskboard.update.ApkProvider
import io.github.veganmarshmallow.taskboard.update.CacheDownloadTarget
import io.github.veganmarshmallow.taskboard.update.InstalledVersion
import io.github.veganmarshmallow.taskboard.update.UpdateBadge
import io.github.veganmarshmallow.taskboard.update.UpdateStore
import io.github.veganmarshmallow.taskboard.widget.WidgetViews
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException

/** The Android side of in-app updates: storage, the APK provider, the installer intents and the widget dot. */
@RunWith(AndroidJUnit4::class)
class UpdateAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val target = CacheDownloadTarget.of(context)

    @After fun cleanUp() {
        UpdateStore(context).save(UpdateStatus.NEVER)
        target.clear()
    }

    @Test fun statusSurvivesANewStoreInstance() {
        val status = UpdateStatus(FakeBoards.NOW, FakeUpdates.V57, UpdateError.LowStorage(30_000_000, 1_000))
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

    @Test fun cacheTargetWritesCommitsAndDiscards() {
        assertTrue(target.availableBytes() > 0)
        target.open().use { it.write(byteArrayOf(1, 2, 3)) }
        assertFalse(target.apk.exists()) // not visible before commit
        target.commit()
        assertArrayEquals(byteArrayOf(1, 2, 3), target.apk.readBytes())
        target.open().use { it.write(byteArrayOf(9)) }
        assertFalse(target.apk.exists()) // a new download removes the old APK first
        target.discard()
        assertFalse(target.apk.exists())
        assertTrue(target.apk.parentFile!!.path.startsWith(context.cacheDir.path))
    }

    @Test fun providerServesOnlyTheDownloadedApk() {
        val resolver = context.contentResolver
        val uri = ApkProvider.uri(context)
        assertEquals("content://${context.packageName}.updates/taskboard-widget.apk", uri.toString())
        try {
            resolver.openInputStream(uri)!!.close()
            fail("served a missing file")
        } catch (e: FileNotFoundException) {
            // expected: nothing downloaded yet
        }
        val bytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 7)
        target.open().use { it.write(bytes) }
        target.commit()
        assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
        assertEquals(ApkProvider.MIME, resolver.getType(uri))
        resolver.query(uri, null, null, null, null)!!.use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("taskboard-widget.apk", c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(5L, c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        val other = uri.buildUpon().path("../shared_prefs/credentials.xml").build()
        assertNull(resolver.getType(other))
        assertNull(resolver.query(other, null, null, null, null))
        try {
            resolver.openInputStream(other)
            fail("served another file")
        } catch (e: FileNotFoundException) {
            // expected
        }
        try {
            resolver.openOutputStream(uri)
            fail("allowed writing")
        } catch (e: Exception) {
            // expected: read-only
        }
    }

    @Test fun installerIntents() {
        val installer = ApkInstaller(context)
        val install = installer.install()
        assertEquals(Intent.ACTION_VIEW, install.action)
        assertEquals(ApkProvider.uri(context), install.data)
        assertEquals(ApkProvider.MIME, install.type)
        assertTrue(install.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val settings = installer.permissionSettings()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settings.action)
        assertEquals("package:${context.packageName}", settings.dataString)
        installer.canInstall() // must not throw: the permission is declared in the manifest
    }
}
