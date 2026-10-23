package io.github.veganmarshmallow.taskboard.core.update

/**
 * Decides what the app's update section shows, from the cached [UpdateStatus], the installed
 * build, and what the section is doing right now. The Android layer only binds [Model] to views.
 */
class UpdatePresenter(private val messages: UpdateMessages) {
    sealed class Activity {
        data object Idle : Activity()
        data object Checking : Activity()

        /** [progress] is null before the first bytes arrive. */
        data class Downloading(val version: VersionInfo?, val progress: DownloadProgress?) : Activity()

        /** The APK is downloaded and verified; the app is about to open Android's installer. */
        data class ReadyToInstall(val version: VersionInfo) : Activity()

        /** A download or install attempt failed; the cached check result is still shown underneath. */
        data class Failed(val error: UpdateError) : Activity()
    }

    data class InstalledBuild(val versionCode: Long, val versionName: String)

    /**
     * [status] is the line to show; [showUpdate] shows the Update button; [progressPercent] is null
     * for no bar, -1 for an indeterminate bar; [busy] disables both buttons.
     */
    data class Model(
        val status: String,
        val isError: Boolean,
        val showUpdate: Boolean,
        val progressPercent: Int?,
        val busy: Boolean,
    )

    fun present(status: UpdateStatus, installed: InstalledBuild, activity: Activity): Model {
        val offer = status.offer(installed.versionCode)
        return when (activity) {
            Activity.Checking -> Model("Checking for updates…", isError = false, showUpdate = false, progressPercent = null, busy = true)
            is Activity.Downloading -> downloading(activity)
            is Activity.ReadyToInstall ->
                // Update stays visible: if the installer didn't open (or was cancelled), tapping it reopens the installer.
                Model("Downloaded ${activity.version.label}. If the installer didn't open, tap Update", isError = false, showUpdate = true, progressPercent = 100, busy = false)
            is Activity.Failed -> Model(messages.message(activity.error), isError = true, showUpdate = offer != null, progressPercent = null, busy = false)
            Activity.Idle -> idle(status, installed, offer)
        }
    }

    private fun idle(status: UpdateStatus, installed: InstalledBuild, offer: AvailableUpdate?): Model {
        val available = offer?.let { "Update available (${it.version.label})" }
        val error = status.error?.let(messages::message)
        val text = when {
            available != null && error != null -> "$available\nLast check failed: $error"
            available != null -> available
            error != null -> error
            status.checkedAt != null -> "Up to date (v${installed.versionName}) · checked ${messages.time(status.checkedAt)}"
            else -> "Installed: v${installed.versionName}"
        }
        return Model(text, isError = error != null && available == null, showUpdate = offer != null, progressPercent = null, busy = false)
    }

    private fun downloading(activity: Activity.Downloading): Model {
        val what = activity.version?.let { "Downloading ${it.label}" } ?: "Downloading"
        val p = activity.progress
        val text = if (p == null) "$what…" else
            "$what… ${p.percent}% (${UpdateMessages.megabytes(p.downloaded)} of ${UpdateMessages.megabytes(p.total)})"
        return Model(text, isError = false, showUpdate = false, progressPercent = p?.percent ?: -1, busy = true)
    }
}
