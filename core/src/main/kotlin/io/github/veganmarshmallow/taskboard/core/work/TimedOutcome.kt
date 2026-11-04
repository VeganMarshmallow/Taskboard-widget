package io.github.veganmarshmallow.taskboard.core.work

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.http.Outcome

/** Folds a time-limited network call into an [Outcome]: a timeout or crash becomes an error, never a hang or a throw. */
fun <T> Timed<Outcome<T>>.toOutcome(): Outcome<T> = when (this) {
    is Timed.Done -> value
    is Timed.Crashed -> Outcome.Failure(crashError(error))
    Timed.TimedOut -> Outcome.Failure(BoardError.Timeout)
}

/**
 * The error for a bug or platform failure inside the app. Safe to show: only the exception's type
 * (for an anonymous class, its nearest named superclass), never its message.
 */
fun crashError(error: Throwable): BoardError =
    BoardError.Internal(generateSequence<Class<*>>(error.javaClass) { it.superclass }.map { it.simpleName }.first { it.isNotEmpty() })
