package io.github.veganmarshmallow.taskboard.core.automerge

import io.github.veganmarshmallow.taskboard.core.board.TitleCleaner
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllText
import io.github.veganmarshmallow.taskboard.core.mergeall.PlannedMerge

/** The auto merge notification's words (#79); a failure's reason is Merge all's own ([MergeAllText.reason]). */
class AutoMergeText(private val mergeAll: MergeAllText) {
    /** Title and body, or null for a skipped poll (nothing to say). */
    fun notice(outcome: AutoMergeOutcome): Pair<String, String>? = when (outcome) {
        is AutoMergeOutcome.Skipped -> null
        is AutoMergeOutcome.Merged -> "Auto merged ${name(outcome.merge)}" to title(outcome.merge)
        is AutoMergeOutcome.Failed -> "Auto merge stopped at ${name(outcome.merge)}" to "${mergeAll.reason(outcome.stop).replaceFirstChar(Char::uppercase)}. ${title(outcome.merge)}"
    }

    private fun name(p: PlannedMerge) = "${p.pr.repo.name} #${p.pr.number}"

    private fun title(p: PlannedMerge) = "#${p.issue} ${TitleCleaner.clean(p.title, TITLE_MAX)}"

    private companion object {
        const val TITLE_MAX = 80
    }
}
