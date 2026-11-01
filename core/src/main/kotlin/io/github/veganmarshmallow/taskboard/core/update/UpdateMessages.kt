package io.github.veganmarshmallow.taskboard.core.update

import java.time.Instant

/** User-facing text for the update section of the app. [formatTime] renders a clock time. */
class UpdateMessages(private val formatTime: (Instant) -> String) {
    fun time(instant: Instant): String = formatTime(instant)

    fun message(error: UpdateError): String = when (error) {
        UpdateError.NoToken -> "Save a GitHub token first"
        UpdateError.Network -> "No network: can't reach GitHub"
        UpdateError.Unreachable -> "Can't reach GitHub (VPN or DNS problem?)"
        UpdateError.NoRepoAccess -> TOKEN_ACCESS_HELP
        UpdateError.BadToken -> "Token rejected (401): wrong or expired"
        UpdateError.NoRelease -> "No published build yet (no \"latest\" release with an APK)"
        UpdateError.ReleaseChanged -> "The build changed during the check. Check for updates again"
        is UpdateError.RateLimited ->
            error.retryAt?.let { "Rate-limited by GitHub, retry after ${formatTime(it)}" }
                ?: "Rate-limited by GitHub, retry in a few minutes"
        is UpdateError.Server -> "GitHub error (${error.status}), try again later"
        is UpdateError.Malformed -> "Couldn't read the release (${error.detail})"
        is UpdateError.Unexpected -> "Update failed (${error.detail})"
    }

    companion object {
        const val TOKEN_ACCESS_HELP =
            "The token can't read Taskboard-widget releases. On GitHub, open Settings → Developer settings → " +
                "Fine-grained tokens and edit this token: under Repository access → Only select repositories, " +
                "add VeganMarshmallow/Taskboard-widget; under Permissions → Repository permissions, set " +
                "Contents to Read-only. Save it; the token itself doesn't change."
    }
}
