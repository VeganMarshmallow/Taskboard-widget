package io.github.veganmarshmallow.taskboard.core.update

import java.time.Instant

/** Everything that can go wrong while checking for, or downloading, an update. */
sealed class UpdateError {
    /** No token saved yet. */
    data object NoToken : UpdateError()

    /** No response at all (offline, DNS, timeout, connection dropped mid-download). */
    data object Network : UpdateError()

    /** The token cannot read Taskboard-widget: the repo isn't added to it, or it lacks Contents: Read-only. */
    data object NoRepoAccess : UpdateError()

    /** 401: the token is wrong or expired. */
    data object BadToken : UpdateError()

    /** The repository is readable but has no `latest` release, or that release has no APK. */
    data object NoRelease : UpdateError()

    /** The release was republished between the check and the download (its assets changed). */
    data object ReleaseChanged : UpdateError()

    /** Asked to update, but the latest release is not newer than the installed build. */
    data object UpToDate : UpdateError()

    data class RateLimited(val retryAt: Instant?) : UpdateError()

    data class Server(val status: Int) : UpdateError()

    /** The release or its version.json could not be understood. */
    data class Malformed(val detail: String) : UpdateError()

    /** The downloaded file is incomplete or not the published one (size, SHA-256, or not an APK). */
    data class Corrupt(val detail: String) : UpdateError()

    /** Not enough free space for the download. */
    data class LowStorage(val neededBytes: Long, val availableBytes: Long) : UpdateError()

    /** Anything else. [detail] never contains the token. */
    data class Unexpected(val detail: String) : UpdateError()
}
