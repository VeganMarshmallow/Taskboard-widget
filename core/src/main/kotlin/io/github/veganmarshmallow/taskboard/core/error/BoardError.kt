package io.github.veganmarshmallow.taskboard.core.error

import java.time.Instant

/** Everything that can go wrong while loading the board or approving a task. */
sealed class BoardError {
    /** No token has been saved yet. */
    data object NoToken : BoardError()

    /** 401, or a 403 that is not a rate limit: the token is wrong, expired, or lacks permission. */
    data class BadToken(val status: Int) : BoardError()

    /** 404: the token cannot see the repository. */
    data object NoAccess : BoardError()

    /** Primary or secondary rate limit. [retryAt] is null when GitHub did not say. */
    data class RateLimited(val retryAt: Instant?) : BoardError()

    /** Approving failed because issue [number] was deleted, transferred, or is no longer visible. */
    data class IssueGone(val number: Int) : BoardError()

    /**
     * The request never got a response and the device had no network at all
     * ([ErrorClassifier.classifyFailure] asks a [NetworkProbe]), or the app knew it was offline
     * before sending anything.
     */
    data object Network : BoardError()

    /**
     * The request never got a response although the device has a network: DNS failed, the
     * connection was refused or reset, or TLS failed. Typical of a VPN or hotspot that is up but
     * not passing traffic. See [Reachability].
     */
    data object Unreachable : BoardError()

    /** The request or the whole refresh took too long (a hanging connection, or a refresh killed mid-way). */
    data object Timeout : BoardError()

    /** 5xx from GitHub. */
    data class Server(val status: Int) : BoardError()

    /** A status code or body we did not expect. [detail] is safe to show (it never contains the token). */
    data class Unexpected(val detail: String) : BoardError()

    /**
     * A bug or platform failure inside the app itself; GitHub may never have been asked.
     * [type] is only the exception's simple class name, so it is safe to show.
     */
    data class Internal(val type: String) : BoardError()
}
