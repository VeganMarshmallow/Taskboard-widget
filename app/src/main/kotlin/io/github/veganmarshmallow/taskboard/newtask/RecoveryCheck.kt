package io.github.veganmarshmallow.taskboard.newtask

import android.content.Context
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.task.SubmitMarker
import io.github.veganmarshmallow.taskboard.core.task.SubmitRecovery
import io.github.veganmarshmallow.taskboard.core.work.guarded

/**
 * Runs [SubmitRecovery] for a leftover [SubmitMarker] on the send thread and hands the result to
 * the main thread: one check at a time, and its result kept until a form shows it. Main thread only.
 */
class RecoveryCheck(private val deps: NewTaskDeps) {
    /** The marker being checked, if any. */
    var checking: SubmitMarker? = null
        private set

    private var unshown: SubmitRecovery.Result? = null

    /** Starts checking [marker] unless a check is already running; [done] runs on the main thread first. */
    fun start(app: Context, marker: SubmitMarker, done: (SubmitRecovery.Result) -> Unit) {
        if (checking != null) return
        checking = marker
        deps.sendExecutor.execute {
            val result = check(app, marker)
            deps.mainThread.execute {
                checking = null
                unshown = result
                done(result)
            }
        }
    }

    /**
     * Any crash, an Error included ([guarded], task #56), is a failed check: the marker stays, so the
     * form says it couldn't tell and checks again next time, and the send thread survives.
     */
    private fun check(app: Context, marker: SubmitMarker): SubmitRecovery.Result {
        val outcome = guarded {
            Outcome.Success(
                SubmitRecovery.check(marker, deps.clock.instant()) { since ->
                    deps.apiProvider(app)?.issuesUpdatedSince(since) ?: Outcome.Failure(BoardError.NoToken)
                },
            )
        }
        return when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> SubmitRecovery.Result.CheckFailed(marker.title, outcome.error)
        }
    }

    /** The finished check's result not yet shown, once. */
    fun take(): SubmitRecovery.Result? = unshown.also { unshown = null }
}
