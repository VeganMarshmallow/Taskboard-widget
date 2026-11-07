package io.github.veganmarshmallow.taskboard.core.automerge

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
