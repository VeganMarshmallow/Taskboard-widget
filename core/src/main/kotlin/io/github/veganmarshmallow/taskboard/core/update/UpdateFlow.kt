package io.github.veganmarshmallow.taskboard.core.update

import io.github.veganmarshmallow.taskboard.core.update.UpdatePresenter.Activity
import java.time.Instant

/**
 * The update check's rules, with no threads or Android: what the update section is doing
 * ([Activity]), when a check starts, and what a finished check caches. The app's `UpdateController`
 * feeds it [Input]s, keeps the returned [Step.activity] and runs [Step.command].
 *
 * [onOpen] decides whether opening the app checks (skipped right after another check, e.g. a
 * rotation); [background] decides whether a widget refresh checks silently.
 */
class UpdateFlow(private val onOpen: UpdateSchedule, private val background: UpdateSchedule) {
    sealed class Input {
        /** The update section was shown. [hasToken] false: the token card already says what to do. */
        data class Opened(val hasToken: Boolean, val cached: UpdateStatus, val now: Instant) : Input()

        /** The Check button, or a newly saved token. */
        data object CheckRequested : Input()

        /** A user-visible check ended; [error] is null on success. */
        data class CheckFinished(val error: UpdateError?) : Input()

        /** Opening the release page failed (e.g. no browser). */
        data class ReleasePageFailed(val error: UpdateError) : Input()
    }

    sealed class Command {
        /** Run a user-visible check, then send [Input.CheckFinished]. */
        data object RunCheck : Command()
    }

    /**
     * The next [activity] and the [command] to run. [refresh]: a check just cached a new status, so the
     * screen must re-render even if [activity] is unchanged (e.g. a second check finishing while Idle).
     */
    data class Step(val activity: Activity, val command: Command? = null, val refresh: Boolean = false)

    /** What a check leaves behind: [status] to cache (null: nothing), whether the widget's dot changed, and the error. */
    data class Recorded(val status: UpdateStatus?, val badgeChanged: Boolean, val error: UpdateError?)

    fun next(current: Activity, input: Input): Step = when (input) {
        is Input.Opened -> opened(current, input)
        Input.CheckRequested -> check(current)
        // Only a missing token is shown as the section's own failure; other errors are cached and shown from the status.
        is Input.CheckFinished -> Step(if (input.error == UpdateError.NoToken) Activity.Failed(UpdateError.NoToken) else Activity.Idle, refresh = true)
        is Input.ReleasePageFailed -> Step(Activity.Failed(input.error))
    }

    /** A silent check during a widget refresh: once a day, or an hour after a failed one. */
    fun backgroundCheckDue(cached: UpdateStatus, now: Instant): Boolean = background.isDue(cached, now)

    /**
     * Applies a check's [result] to [before]. A null result means no token: nothing is cached, because
     * it isn't a real check.
     */
    fun record(before: UpdateStatus, result: UpdateResult<AvailableUpdate>?, now: Instant, installedVersionCode: Long): Recorded {
        if (result == null) return Recorded(null, badgeChanged = false, error = UpdateError.NoToken)
        val after = before.withResult(result, now)
        val changed = (before.offer(installedVersionCode) != null) != (after.offer(installedVersionCode) != null)
        return Recorded(after, changed, (result as? UpdateResult.Err)?.error)
    }

    private fun check(current: Activity): Step =
        if (current == Activity.Checking) Step(current) else Step(Activity.Checking, Command.RunCheck)

    private fun opened(current: Activity, input: Input.Opened): Step = when {
        current == Activity.Checking -> Step(current) // keep it (e.g. after a rotation)
        input.hasToken && onOpen.isDue(input.cached, input.now) -> check(Activity.Idle)
        else -> Step(Activity.Idle) // a shown failure is cleared on open
    }
}
