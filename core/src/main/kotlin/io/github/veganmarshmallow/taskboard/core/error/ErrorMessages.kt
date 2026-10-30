package io.github.veganmarshmallow.taskboard.core.error

import java.time.Instant

/**
 * One-line, user-facing messages. [formatTime] renders a clock time in the user's locale
 * (e.g. "14:05"). [message] is context-free (used on the setup screen); [widgetMessage] adds
 * what tapping the widget's message will do.
 */
class ErrorMessages(private val formatTime: (Instant) -> String) {
    fun message(error: BoardError): String = when (error) {
        BoardError.NoToken -> "No GitHub token set"
        is BoardError.BadToken ->
            if (error.status == 401) "Token rejected (401): wrong or expired"
            else "Token not allowed (403): it needs Issues read & write"
        BoardError.NoAccess -> "Repo not found (404): token has no access to claude-workspace"
        is BoardError.RateLimited ->
            error.retryAt?.let { "Rate-limited by GitHub, retry after ${formatTime(it)}" }
                ?: "Rate-limited by GitHub, retry in a few minutes"
        is BoardError.IssueGone -> "Issue #${error.number} not found: deleted, moved, or no longer visible"
        BoardError.Network -> "No network: can't reach GitHub"
        BoardError.Unreachable -> "Can't reach GitHub (VPN or DNS problem?)"
        BoardError.Timeout -> "GitHub took too long to answer (slow network?)"
        is BoardError.Server -> "GitHub error (${error.status}), try again later"
        is BoardError.Unexpected -> "Unexpected response from GitHub (${error.detail})"
    }

    fun widgetMessage(error: BoardError): String = message(error) + when {
        error is BoardError.NoToken -> " · tap to set up"
        opensSetup(error) -> " · tap to fix"
        else -> " · tap to retry"
    }

    /** Whether tapping the message should open the token setup screen (vs. just retrying). */
    fun opensSetup(error: BoardError): Boolean =
        error is BoardError.NoToken || error is BoardError.BadToken || error is BoardError.NoAccess
}
