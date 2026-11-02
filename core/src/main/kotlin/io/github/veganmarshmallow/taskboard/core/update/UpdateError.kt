package io.github.veganmarshmallow.taskboard.core.update

import java.time.Instant

/** Everything that can go wrong while checking for an update, or opening its release page. */
sealed class UpdateError {
    /** No token saved yet. */
    data object NoToken : UpdateError()

    /** No response at all, and the device had no network. */
    data object Network : UpdateError()

    /** No response at all although the device has a network (DNS, VPN, timeout, connection dropped). */
    data object Unreachable : UpdateError()

    /** The token cannot read Taskboard-widget: the repo isn't added to it, or it lacks Contents: Read-only. */
    data object NoRepoAccess : UpdateError()

    /** 401: the token is wrong or expired. */
    data object BadToken : UpdateError()

    /** The repository is readable but has no `latest` release, or that release has no APK. */
    data object NoRelease : UpdateError()

    /** The release was republished while it was being read (its assets changed). */
    data object ReleaseChanged : UpdateError()

    data class RateLimited(val retryAt: Instant?) : UpdateError()

    data class Server(val status: Int) : UpdateError()

    /** The release or its version.json could not be understood. */
    data class Malformed(val detail: String) : UpdateError()

    /** Anything else. [detail] never contains the token. */
    data class Unexpected(val detail: String) : UpdateError()
}
