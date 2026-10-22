package io.github.veganmarshmallow.taskboard.core.http

import io.github.veganmarshmallow.taskboard.core.error.BoardError

/** Success with a value, or a classified [BoardError]. */
sealed class Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>()
    data class Failure(val error: BoardError) : Outcome<Nothing>()

    inline fun <R> map(transform: (T) -> R): Outcome<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }
}
