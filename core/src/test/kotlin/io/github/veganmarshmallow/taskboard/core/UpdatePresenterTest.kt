package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.update.AvailableUpdate
import io.github.veganmarshmallow.taskboard.core.update.UpdateError
import io.github.veganmarshmallow.taskboard.core.update.UpdateMessages
import io.github.veganmarshmallow.taskboard.core.update.UpdatePresenter
import io.github.veganmarshmallow.taskboard.core.update.UpdatePresenter.Activity
import io.github.veganmarshmallow.taskboard.core.update.UpdatePresenter.InstalledBuild
import io.github.veganmarshmallow.taskboard.core.update.UpdateStatus
import io.github.veganmarshmallow.taskboard.core.update.VersionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePresenterTest {
    private val presenter = UpdatePresenter(UpdateMessages { "14:05" })
    private val installed = InstalledBuild(41, "0.1.41")
    private val v42 = AvailableUpdate(VersionInfo(42, "0.1.42"), RELEASE_PAGE)

    @Test fun neverChecked() {
        val m = presenter.present(UpdateStatus.NEVER, installed, Activity.Idle)
        assertEquals("Installed: v0.1.41", m.status)
        assertFalse(m.showUpdate); assertFalse(m.isError); assertFalse(m.busy)
    }

    @Test fun updateAvailableSaysTheBrowserDownloadsIt() {
        val m = presenter.present(UpdateStatus(NOW, v42), installed, Activity.Idle)
        assertEquals("Update available (v0.1.42): tap Update to download it in your browser", m.status)
        assertTrue(m.showUpdate)
        assertFalse(m.isError); assertFalse(m.busy)
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
        assertTrue(m.status.contains("Contents to Read-only"))
        assertFalse(m.showUpdate)
    }

    @Test fun failedCheckKeepsOfferingTheKnownUpdate() {
        val m = presenter.present(UpdateStatus(NOW, v42, UpdateError.Network), installed, Activity.Idle)
        assertTrue(m.status.startsWith("Update available (v0.1.42): tap Update to download it in your browser\nLast check failed: No network"))
        assertTrue(m.showUpdate)
        assertFalse(m.isError)
    }

    @Test fun checking() {
        val m = presenter.present(UpdateStatus(NOW, v42), installed, Activity.Checking)
        assertEquals("Checking for updates…", m.status)
        assertTrue(m.busy); assertFalse(m.showUpdate)
    }

    @Test fun failureKeepsTheUpdateButtonOnlyWhileAnUpdateIsOffered() {
        val noBrowser = presenter.present(UpdateStatus(NOW, v42), installed, Activity.Failed(UpdateError.Unexpected("no browser to open the release page")))
        assertTrue(noBrowser.isError)
        assertTrue(noBrowser.showUpdate)
        assertTrue(noBrowser.status.contains("no browser"))
        val noToken = presenter.present(UpdateStatus.NEVER, installed, Activity.Failed(UpdateError.NoToken))
        assertEquals("Save a GitHub token first", noToken.status)
        assertFalse(noToken.showUpdate)
    }

    @Test fun hebrewVersionNameRendersAsIs() {
        val m = presenter.present(UpdateStatus.NEVER, InstalledBuild(1, "1.0-בטא"), Activity.Idle)
        assertEquals("Installed: v1.0-בטא", m.status)
        val offered = presenter.present(UpdateStatus(NOW, v42.copy(version = VersionInfo(42, "2.0-בטא"))), installed, Activity.Idle)
        assertTrue(offered.status.startsWith("Update available (v2.0-בטא)"))
    }
}
