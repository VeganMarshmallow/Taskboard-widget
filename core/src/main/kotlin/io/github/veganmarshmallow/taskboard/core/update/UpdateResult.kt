package io.github.veganmarshmallow.taskboard.core.update

/** A value, or the [UpdateError] that stopped the update step. */
sealed class UpdateResult<out T> {
    data class Ok<T>(val value: T) : UpdateResult<T>()
    data class Err(val error: UpdateError) : UpdateResult<Nothing>()

    inline fun <R> flatMap(next: (T) -> UpdateResult<R>): UpdateResult<R> = when (this) {
        is Ok -> next(value)
        is Err -> this
    }

    inline fun <R> map(transform: (T) -> R): UpdateResult<R> = flatMap { Ok(transform(it)) }
}
