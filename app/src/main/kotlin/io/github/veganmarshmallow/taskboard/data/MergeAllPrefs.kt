package io.github.veganmarshmallow.taskboard.data

import android.annotation.SuppressLint
import android.content.Context
import io.github.veganmarshmallow.taskboard.core.mergeall.LockedMergeAllStore
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllRecords
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStore

/**
 * The Merge all run on disk (#57). Saved synchronously, so a process death right after a step
 * finds that step's state. Cancel is a separate key holding the run's id, so it is never lost to a
 * step saving the state at the same moment, and never carries over to a later run.
 *
 * #60: [store] puts every operation under one process-wide [LOCK], so starting a run is a
 * compare-and-set even across store instances (each caller makes its own).
 */
@SuppressLint("ApplySharedPref")
class MergeAllPrefs(context: Context) : MergeAllRecords {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun readState(): String? = prefs.getString(STATE, null)

    override fun writeState(text: String) {
        prefs.edit().putString(STATE, text).commit()
    }

    override fun readCancel(): String? = prefs.getString(CANCEL, null)

    override fun writeCancel(runId: String) {
        prefs.edit().putString(CANCEL, runId).commit()
    }

    override fun clear() {
        prefs.edit().remove(STATE).remove(CANCEL).commit()
    }

    companion object {
        private const val FILE = "merge_all"
        private const val STATE = "state"
        private const val CANCEL = "cancel"

        /** One lock for the run's file in this process. */
        private val LOCK = Any()

        fun store(context: Context): MergeAllStore = LockedMergeAllStore(MergeAllPrefs(context), LOCK)
    }
}
