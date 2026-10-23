package io.github.veganmarshmallow.taskboard.core.snapshot

/** Where the last [WidgetState] is kept between refreshes and processes. */
interface WidgetStateStore {
    fun load(): WidgetState
    fun save(state: WidgetState)
}
