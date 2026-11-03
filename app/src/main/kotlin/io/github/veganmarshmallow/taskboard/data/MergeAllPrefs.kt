package io.github.veganmarshmallow.taskboard.data

import android.annotation.SuppressLint
import android.content.Context
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllState
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStateCodec
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStore

/**
 * The Merge all run on disk (#57). Saved synchronously, so a process death right after a step
 * finds that step's state. Cancel is a separate key holding the run's id, so it is never lost to a
 * step saving the state at the same moment, and never carries over to a later run.
 */
@SuppressLint("ApplySharedPref")
class MergeAllPrefs(context: Context) : MergeAllStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): MergeAllState? = MergeAllStateCodec.decode(prefs.getString(STATE, null))

    override fun save(state: MergeAllState) {
        prefs.edit().putString(STATE, MergeAllStateCodec.encode(state)).commit()
    }

    override fun clear() {
        prefs.edit().remove(STATE).remove(CANCEL).commit()
    }

    override fun requestCancel(runId: String) {
        prefs.edit().putString(CANCEL, runId).commit()
    }

    override fun cancelRequested(runId: String): Boolean = prefs.getString(CANCEL, null) == runId

    private companion object {
        const val FILE = "merge_all"
        const val STATE = "state"
        const val CANCEL = "cancel"
    }
}
