package io.github.veganmarshmallow.taskboard.update

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.update.UpdateError
import io.github.veganmarshmallow.taskboard.core.update.UpdateMessages
import io.github.veganmarshmallow.taskboard.core.update.UpdatePresenter
import io.github.veganmarshmallow.taskboard.widget.TimeText

/**
 * The "App updates" card on the main screen (`section_update.xml`). Binds [UpdatePresenter.Model]
 * to views and handles the two buttons; all decisions come from core. Call [onOpen] from onCreate,
 * [onResume] and [onDestroy] from the matching lifecycle methods.
 */
class UpdateSection(private val activity: Activity) {
    private val card = UpdateCard(activity.findViewById(R.id.update_section))
    private val presenter = UpdatePresenter(UpdateMessages(TimeText(activity)::format))
    private val installer = ApkInstaller(activity)

    /** Set when the user was sent to the "install unknown apps" setting; resumes the update on return. */
    private var waitingForPermission = false

    fun onOpen() {
        card.checkButton.setOnClickListener { UpdateController.check(activity) }
        card.updateButton.setOnClickListener { onUpdateTapped() }
        UpdateController.listen(::render)
        UpdateController.onReadyToInstall = { launchInstaller() }
        when (UpdateController.activity) {
            UpdatePresenter.Activity.Idle -> { dropStaleDownload(); UpdateController.checkOnOpen(activity) }
            is UpdatePresenter.Activity.Failed -> { UpdateController.reset(); dropStaleDownload(); UpdateController.checkOnOpen(activity) }
            else -> Unit // checking, downloading, or downloaded: keep it (e.g. after a rotation)
        }
        render()
    }

    fun onResume() {
        if (waitingForPermission && installer.canInstall()) {
            waitingForPermission = false
            UpdateController.download(activity)
        }
        render()
    }

    /** A new token may grant access that the last check lacked. */
    fun onTokenChanged() = UpdateController.check(activity)

    fun onDestroy() {
        UpdateController.listen(null)
        UpdateController.onReadyToInstall = null
    }

    private fun render() {
        if (activity.isDestroyed) return
        card.bind(presenter.present(UpdateStore(activity).load(), InstalledVersion.of(activity), UpdateController.activity))
    }

    private fun onUpdateTapped() {
        if (UpdateController.activity is UpdatePresenter.Activity.ReadyToInstall && CacheDownloadTarget.of(activity).apk.isFile) {
            launchInstaller()
            return
        }
        if (installer.canInstall()) {
            UpdateController.download(activity)
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_permission_title)
            .setMessage(R.string.update_permission_message)
            .setPositiveButton(R.string.update_permission_open) { _, _ -> openPermissionSettings() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openPermissionSettings() {
        try {
            waitingForPermission = true
            activity.startActivity(installer.permissionSettings())
        } catch (e: ActivityNotFoundException) {
            waitingForPermission = false
            UpdateController.fail(UpdateError.Unexpected("can't open the install permission setting"))
        }
    }

    private fun launchInstaller() {
        try {
            activity.startActivity(installer.install())
        } catch (e: ActivityNotFoundException) {
            UpdateController.fail(UpdateError.Unexpected("no app can install APKs"))
        } catch (e: SecurityException) {
            UpdateController.fail(UpdateError.Unexpected("the installer was blocked"))
        }
    }

    /** A cached APK that is not newer than what runs (it was installed, or is outdated) is deleted. */
    private fun dropStaleDownload() {
        if (UpdateStore(activity).load().offer(InstalledVersion.of(activity).versionCode) == null) {
            CacheDownloadTarget.of(activity).clear()
        }
    }
}
