package io.github.veganmarshmallow.taskboard.core.update

import java.time.Instant

/** User-facing text for the update section of the app. [formatTime] renders a clock time. */
class UpdateMessages(private val formatTime: (Instant) -> String) {
    fun time(instant: Instant): String = formatTime(instant)

    fun message(error: UpdateError): String = when (error) {
        UpdateError.NoToken -> "Save a GitHub token first"
        UpdateError.Network -> "No network: can't reach GitHub"
        UpdateError.NoRepoAccess -> TOKEN_ACCESS_HELP
        UpdateError.BadToken -> "Token rejected (401): wrong or expired"
        UpdateError.NoRelease -> "No published build yet (no \"latest\" release with an APK)"
        UpdateError.ReleaseChanged -> "The build changed while downloading. Check for updates again"
        UpdateError.UpToDate -> "Already up to date"
        is UpdateError.RateLimited ->
            error.retryAt?.let { "Rate-limited by GitHub, retry after ${formatTime(it)}" }
                ?: "Rate-limited by GitHub, retry in a few minutes"
        is UpdateError.Server -> "GitHub error (${error.status}), try again later"
        is UpdateError.Malformed -> "Couldn't read the release (${error.detail})"
        is UpdateError.Corrupt -> "Download damaged (${error.detail}). Try again"
        is UpdateError.Untrusted -> "Update blocked: the downloaded build isn't this app signed with its key (${error.detail}). It was deleted, not installed"
        UpdateError.InstallNotAllowed -> "Install not allowed. In Settings → Install unknown apps, turn on \"Allow from this source\" for Claude board, then tap Update"
        is UpdateError.LowStorage ->
            "Not enough free space: needs ${megabytes(error.neededBytes)}, " +
                (if (error.availableBytes >= 0) "${megabytes(error.availableBytes)} free" else "free space unknown")
        is UpdateError.Unexpected -> "Update failed (${error.detail})"
    }

    companion object {
        const val TOKEN_ACCESS_HELP =
            "The token can't read Taskboard-widget releases. On GitHub, open Settings → Developer settings → " +
                "Fine-grained tokens and edit this token: under Repository access → Only select repositories, " +
                "add VeganMarshmallow/Taskboard-widget; under Permissions → Repository permissions, set " +
                "Contents to Read-only. Save it; the token itself doesn't change."

        /** "12.3 MB", in decimal megabytes like Android's storage settings. */
        fun megabytes(bytes: Long): String {
            val b = bytes.coerceAtLeast(0)
            val tenths = b / 100_000 + if (b % 100_000 >= 50_000) 1 else 0 // no overflow near Long.MAX_VALUE
            return "${tenths / 10}.${tenths % 10} MB"
        }
    }
}
