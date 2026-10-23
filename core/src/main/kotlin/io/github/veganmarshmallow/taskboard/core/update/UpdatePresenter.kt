package io.github.veganmarshmallow.taskboard.core.update

/**
 * Decides what the app's update section shows, from the cached [UpdateStatus], the installed
 * build, and what the section is doing right now. The Android layer only binds [Model] to views.
 */
class UpdatePresenter(private val messages: UpdateMessages) {
    sealed class Activity {
        data object Idle : Activity()
        data object Checking : Activity()

        /** A check or opening the release page failed; the cached check result still decides the button. */
        data class Failed(val error: UpdateError) : Activity()
    }

    data class InstalledBuild(val versionCode: Long, val versionName: String)

    /**
     * [status] is the line to show; [showUpdate] shows the Update button (which opens the release
     * page in the browser); [busy] disables both buttons.
     */
    data class Model(
        val status: String,
        val isError: Boolean,
        val showUpdate: Boolean,
        val busy: Boolean,
    )

    fun present(status: UpdateStatus, installed: InstalledBuild, activity: Activity): Model {
        val offer = status.offer(installed.versionCode)
        return when (activity) {
            Activity.Checking -> Model("Checking for updates…", isError = false, showUpdate = false, busy = true)
            is Activity.Failed -> Model(messages.message(activity.error), isError = true, showUpdate = offer != null, busy = false)
            Activity.Idle -> idle(status, installed, offer)
        }
    }

    private fun idle(status: UpdateStatus, installed: InstalledBuild, offer: AvailableUpdate?): Model {
        val available = offer?.let { "Update available (${it.version.label}): tap Update to download it in your browser" }
        val error = status.error?.let(messages::message)
        val text = when {
            available != null && error != null -> "$available\nLast check failed: $error"
            available != null -> available
            error != null -> error
            status.checkedAt != null -> "Up to date (v${installed.versionName}) · checked ${messages.time(status.checkedAt)}"
            else -> "Installed: v${installed.versionName}"
        }
        return Model(text, isError = error != null && available == null, showUpdate = offer != null, busy = false)
    }
}
