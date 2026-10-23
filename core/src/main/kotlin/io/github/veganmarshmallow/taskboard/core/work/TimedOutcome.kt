package io.github.veganmarshmallow.taskboard.core.work

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.http.Outcome

/** Folds a time-limited network call into an [Outcome]: a timeout or crash becomes an error, never a hang or a throw. */
fun <T> Timed<Outcome<T>>.toOutcome(): Outcome<T> = when (this) {
    is Timed.Done -> value
    is Timed.Crashed -> Outcome.Failure(crashError(error))
    Timed.TimedOut -> Outcome.Failure(BoardError.Timeout)
}

/** The error for a bug or platform failure inside background work. Safe to show: only the exception's type. */
fun crashError(error: Throwable): BoardError = BoardError.Unexpected("internal error: ${error.javaClass.simpleName}")
