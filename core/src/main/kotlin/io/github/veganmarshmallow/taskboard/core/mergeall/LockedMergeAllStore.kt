package io.github.veganmarshmallow.taskboard.core.mergeall

/**
 * The keys a Merge all run is kept under (#57): the encoded state ([MergeAllStateCodec]) and the id
 * of the run Cancel was tapped for. Each call stands alone; [LockedMergeAllStore] makes the
 * read-then-write ones atomic.
 */
interface MergeAllRecords {
    fun readState(): String?
    fun writeState(text: String)
    fun readCancel(): String?
    fun writeCancel(runId: String)

    /** Forgets both keys. */
    fun clear()
}

/**
 * A [MergeAllStore] over [records] with every operation under [lock] (#60), so a check and the
 * write it decides on happen as one step. [lock] must be one object for the whole process (every
 * store over the same records shares it), since key-value storage such as SharedPreferences has
 * no read-and-write of its own.
 */
class LockedMergeAllStore(private val records: MergeAllRecords, private val lock: Any) : MergeAllStore {
    override fun load(): MergeAllState? = synchronized(lock) { MergeAllStateCodec.decode(records.readState()) }

    override fun start(state: MergeAllState): Boolean = synchronized(lock) {
        if (load()?.running == true) return false
        records.writeState(MergeAllStateCodec.encode(state))
        true
    }

    override fun saveIf(state: MergeAllState): Boolean = synchronized(lock) {
        val saved = load()
        if (saved == null || saved.runId != state.runId || !saved.running) return false
        records.writeState(MergeAllStateCodec.encode(state))
        true
    }

    override fun save(state: MergeAllState) = synchronized(lock) { records.writeState(MergeAllStateCodec.encode(state)) }

    override fun clear() = synchronized(lock) { records.clear() }

    override fun requestCancel(runId: String) = synchronized(lock) { records.writeCancel(runId) }

    override fun cancelRequested(runId: String): Boolean = synchronized(lock) { records.readCancel() == runId }
}
