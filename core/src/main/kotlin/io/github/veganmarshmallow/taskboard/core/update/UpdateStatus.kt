package io.github.veganmarshmallow.taskboard.core.update

import java.time.Instant

/**
 * The cached result of the last update check. [available] is the latest release, whether or not it
 * is newer than what is installed; [error] is set when the last check failed (the previous
 * [available] is kept, like the board keeps a stale board under an error).
 */
data class UpdateStatus(
    val checkedAt: Instant? = null,
    val available: AvailableUpdate? = null,
    val error: UpdateError? = null,
) {
    /** The update to offer, or null when the installed build is current. */
    fun offer(installedVersionCode: Long): AvailableUpdate? = available?.takeIf { it.isNewerThan(installedVersionCode) }

    fun withResult(result: UpdateResult<AvailableUpdate>, now: Instant): UpdateStatus = when (result) {
        is UpdateResult.Ok -> UpdateStatus(now, result.value, null)
        is UpdateResult.Err -> UpdateStatus(now, available, result.error)
    }

    companion object {
        val NEVER = UpdateStatus()
    }
}
