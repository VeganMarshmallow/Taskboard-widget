package io.github.veganmarshmallow.taskboard.core.automerge

import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStop
import io.github.veganmarshmallow.taskboard.core.mergeall.PlannedMerge

/** What one auto merge poll (#79) did. The app notifies on [Merged] and [Failed], never on [Skipped]. */
sealed class AutoMergeOutcome {
    enum class Skip {
        /** The setting is off (the default). */
        DISABLED,

        /** A Merge all run is going on: it merges in its own order, auto merge waits for it. */
        MERGE_ALL_RUNNING,

        /** The PRs couldn't be listed or checked (network, token…): the board shows that error. */
        CANT_CHECK,

        /** No `claude/…` PR is ready. */
        NOTHING_READY,

        /** The chosen PR changed since the check (new head, behind, checks running, merged elsewhere): next poll. */
        NOT_READY_YET,
    }

    data class Skipped(val why: Skip) : AutoMergeOutcome()

    data class Merged(val merge: PlannedMerge) : AutoMergeOutcome()

    /** [merge] could not be merged, for a reason that won't clear by itself. */
    data class Failed(val merge: PlannedMerge, val stop: MergeAllStop) : AutoMergeOutcome() {
        /** The same failure on later polls has the same key, so the app notifies it once. */
        val key: String get() = "${merge.pr}:${stop.kind}:${stop.detail.orEmpty()}"
    }
}
