package io.github.veganmarshmallow.taskboard.core.pace

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.http.Outcome

/**
 * The Work pace section of settings (#83): which option is checked, whether it can be changed, and
 * what the status line says. The app renders it and runs the reads and saves it asks for.
 * It opens on the last value this phone knew ([loading]) while the file is read; a failed save
 * puts back the option that is still stored.
 */
data class WorkPaceForm(
    val shown: WorkPace,
    val phase: Phase,
    val error: BoardError? = null,
    /** The value stored before the save in flight (shown again if it fails). */
    private val stored: WorkPace = shown,
) {
    enum class Phase { LOADING, LOAD_FAILED, READY, SAVING, SAVED, SAVE_FAILED }

    /** The options can be tapped: the stored value is known and no save is in flight. */
    val enabled: Boolean get() = phase == Phase.READY || phase == Phase.SAVED || phase == Phase.SAVE_FAILED

    /** A "Try again" for a failed read. */
    val canRetry: Boolean get() = phase == Phase.LOAD_FAILED

    /** A save refused the way a token without Contents: Read and write on the board repo is refused. */
    val needsWriteAccess: Boolean
        get() = phase == Phase.SAVE_FAILED && (error == BoardError.NoAccess || (error as? BoardError.BadToken)?.status == 403)

    fun reloading(): WorkPaceForm = copy(phase = Phase.LOADING, error = null)

    fun loaded(read: Outcome<WorkPace>): WorkPaceForm = when (read) {
        is Outcome.Success -> WorkPaceForm(read.value, Phase.READY)
        is Outcome.Failure -> copy(phase = Phase.LOAD_FAILED, error = read.error)
    }

    /** The form while [pace] is saved; null when the tap changes nothing (disabled, or already shown). */
    fun picked(pace: WorkPace): WorkPaceForm? =
        if (!enabled || pace == shown) null else WorkPaceForm(pace, Phase.SAVING, stored = shown)

    fun saved(result: Outcome<WorkPace>): WorkPaceForm = when (result) {
        is Outcome.Success -> WorkPaceForm(result.value, Phase.SAVED)
        is Outcome.Failure -> WorkPaceForm(stored, Phase.SAVE_FAILED, result.error)
    }

    companion object {
        fun loading(lastKnown: WorkPace): WorkPaceForm = WorkPaceForm(lastKnown, Phase.LOADING)
    }
}
