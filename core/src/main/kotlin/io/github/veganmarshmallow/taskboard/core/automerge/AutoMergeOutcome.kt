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

/**
 * When a poll's outcome is notified (#79): every merge; a failure once, not again on every later
 * poll that hits the same failure. [last]: the failure notified last ([AutoMergeOutcome.Failed.key]).
 */
object AutoMergeNotices {
    fun shouldNotify(last: String?, outcome: AutoMergeOutcome): Boolean = when (outcome) {
        is AutoMergeOutcome.Merged -> true
        is AutoMergeOutcome.Failed -> outcome.key != last
        is AutoMergeOutcome.Skipped -> false
    }

    /**
     * The failure to remember after [outcome]. A merge, or nothing left to merge, forgets it (the next
     * failure is news); a skip that proves nothing (network, a wait, the setting off) keeps it.
     */
    fun remembered(last: String?, outcome: AutoMergeOutcome): String? = when (outcome) {
        is AutoMergeOutcome.Failed -> outcome.key
        is AutoMergeOutcome.Merged -> null
        is AutoMergeOutcome.Skipped -> if (outcome.why == AutoMergeOutcome.Skip.NOTHING_READY) null else last
    }
}
