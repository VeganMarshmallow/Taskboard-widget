package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.update.AvailableUpdate
import io.github.veganmarshmallow.taskboard.core.update.DownloadProgress
import io.github.veganmarshmallow.taskboard.core.update.ReleaseAsset
import io.github.veganmarshmallow.taskboard.core.update.UpdateError
import io.github.veganmarshmallow.taskboard.core.update.UpdateMessages
import io.github.veganmarshmallow.taskboard.core.update.UpdatePresenter
import io.github.veganmarshmallow.taskboard.core.update.UpdatePresenter.Activity
import io.github.veganmarshmallow.taskboard.core.update.UpdatePresenter.InstalledBuild
import io.github.veganmarshmallow.taskboard.core.update.UpdateStatus
import io.github.veganmarshmallow.taskboard.core.update.VersionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePresenterTest {
    private val presenter = UpdatePresenter(UpdateMessages { "14:05" })
    private val installed = InstalledBuild(41, "0.1.41")
    private val v42 = AvailableUpdate(VersionInfo(42, "0.1.42"), ReleaseAsset(APK_ID, "taskboard-widget.apk", 700_000, assetUrl(APK_ID)))

    @Test fun neverChecked() {
        val m = presenter.present(UpdateStatus.NEVER, installed, Activity.Idle)
        assertEquals("Installed: v0.1.41", m.status)
        assertFalse(m.showUpdate); assertFalse(m.isError); assertFalse(m.busy); assertNull(m.progressPercent)
    }

    @Test fun updateAvailable() {
        val m = presenter.present(UpdateStatus(NOW, v42), installed, Activity.Idle)
        assertEquals("Update available (v0.1.42)", m.status)
        assertTrue(m.showUpdate)
    }

    @Test fun upToDateShowsTheCheckTime() {
        val m = presenter.present(UpdateStatus(NOW, v42), InstalledBuild(42, "0.1.42"), Activity.Idle)
        assertEquals("Up to date (v0.1.42) · checked 14:05", m.status)
        assertFalse(m.showUpdate)
        // A local build newer than the release (e.g. a preview build) is also "up to date".
        assertFalse(presenter.present(UpdateStatus(NOW, v42), InstalledBuild(99, "0.1.99"), Activity.Idle).showUpdate)
    }

    @Test fun failedCheckWithoutKnownUpdateIsAnError() {
        val m = presenter.present(UpdateStatus(NOW, null, UpdateError.NoRepoAccess), installed, Activity.Idle)
        assertTrue(m.isError)
        assertTrue(m.status.contains("Contents: Read-only"))
        assertFalse(m.showUpdate)
    }

    @Test fun failedCheckKeepsOfferingTheKnownUpdate() {
        val m = presenter.present(UpdateStatus(NOW, v42, UpdateError.Network), installed, Activity.Idle)
        assertTrue(m.status.startsWith("Update available (v0.1.42)\nLast check failed: No network"))
        assertTrue(m.showUpdate)
        assertFalse(m.isError)
    }

    @Test fun checking() {
        val m = presenter.present(UpdateStatus(NOW, v42), installed, Activity.Checking)
        assertTrue(m.busy); assertFalse(m.showUpdate)
    }

    @Test fun downloading() {
        val start = presenter.present(UpdateStatus(NOW, v42), installed, Activity.Downloading(v42.version, null))
        assertEquals("Downloading v0.1.42…", start.status)
        assertEquals(-1, start.progressPercent)
        assertTrue(start.busy)
        val mid = presenter.present(UpdateStatus(NOW, v42), installed, Activity.Downloading(v42.version, DownloadProgress(350_000, 700_000)))
        assertEquals("Downloading v0.1.42… 50% (0.4 MB of 0.7 MB)", mid.status)
        assertEquals(50, mid.progressPercent)
        assertFalse(mid.showUpdate)
        assertEquals("Downloading…", presenter.present(UpdateStatus.NEVER, installed, Activity.Downloading(null, null)).status)
    }

    @Test fun failedDownloadOffersRetry() {
        val m = presenter.present(UpdateStatus(NOW, v42), installed, Activity.Failed(UpdateError.Corrupt("SHA-256 mismatch")))
        assertTrue(m.isError)
        assertTrue(m.showUpdate)
        assertTrue(m.status.contains("SHA-256 mismatch"))
        val gone = presenter.present(UpdateStatus.NEVER, installed, Activity.Failed(UpdateError.UpToDate))
        assertFalse(gone.showUpdate)
    }

    @Test fun readyToInstall() {
        val m = presenter.present(UpdateStatus(NOW, v42), installed, Activity.ReadyToInstall(v42.version))
        assertEquals("Downloaded v0.1.42. If the installer didn't open, tap Update", m.status)
        assertEquals(100, m.progressPercent)
        assertTrue(m.showUpdate); assertFalse(m.busy)
    }

    @Test fun hebrewVersionNameRendersAsIs() {
        val m = presenter.present(UpdateStatus.NEVER, InstalledBuild(1, "1.0-בטא"), Activity.Idle)
        assertEquals("Installed: v1.0-בטא", m.status)
    }
}
