package io.github.veganmarshmallow.taskboard.core.snapshot

/**
 * The single source of what the widget shows: the state on [disk], unless the last save failed,
 * in which case the error state that could not be saved (task #37). Every render reads it, so a
 * failed save still shows its error banner without anyone drawing a state handed in from outside.
 *
 * The unsaved state lives in memory only. The next successful [save] clears it. If the process
 * dies first it is simply gone: the widget falls back to the last board that really was saved,
 * which is still correct (only the transient "couldn't save" banner is lost, and the next refresh
 * retries). Keeping it in memory means it can never outlive the board it was put over.
 *
 * Use one instance per process, so every reader sees the same unsaved state.
 */
class ShownStateStore(private val disk: WidgetStateStore) : WidgetStateStore {
    @Volatile private var unsaved: WidgetState? = null

    override fun load(): WidgetState = unsaved ?: disk.load()

    /** Saves to [disk]; on success the unsaved state is dropped. A failed save keeps it and throws. */
    override fun save(state: WidgetState) {
        disk.save(state)
        unsaved = null
    }

    /** Shows [state] until the next successful [save], without writing it to disk (it failed to save). */
    fun showUnsaved(state: WidgetState) {
        unsaved = state
    }
}
