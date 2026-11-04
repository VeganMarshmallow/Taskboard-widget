package io.github.veganmarshmallow.taskboard.core.mergeall

import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.board.TitleCleaner
import io.github.veganmarshmallow.taskboard.core.merge.MergeButton
import io.github.veganmarshmallow.taskboard.core.merge.MergeDialog
import io.github.veganmarshmallow.taskboard.core.merge.MergeDialogs
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStop.Kind
import io.github.veganmarshmallow.taskboard.core.model.Repo

/** Every word Merge all shows (#57): its dialogs, the widget's progress line, and the notification. */
class MergeAllText(private val dialogs: MergeDialogs, private val board: Repo = Repo.BOARD) {
    fun planning() = MergeDialog(
        MergeDialog.Kind.PROGRESS, "Checking the PRs…", listOf("Finding every PR that is ready to merge, and the order to merge them in."),
        primary = null, secondary = MergeButton.CANCEL, busy = true,
    )

    /** "Merge 5 PRs in this order: …", one line per PR. */
    fun confirm(plan: List<PlannedMerge>) = MergeDialog(
        MergeDialog.Kind.CONFIRM, "Merge ${count(plan.size)}?",
        listOf("Merge ${count(plan.size)} in this order:") +
            plan.mapIndexed { i, p -> "${i + 1}. ${name(p)}: ${TitleCleaner.clean(p.title, TITLE_MAX)}" } +
            "Each one is updated with its default branch, waits for all its checks, then merges. The first problem stops the run; " +
            "PRs merged before it stay merged. It runs in the background: you can close this.",
        primary = MergeButton.MERGE_ALL, secondary = MergeButton.CANCEL,
    )

    fun nothingToMerge() = MergeDialog(
        MergeDialog.Kind.REFUSAL, "Nothing to merge", listOf("No PR is ready to merge right now. Each Merge button says why."),
        primary = null, secondary = MergeButton.CLOSE,
    )

    /** The plan couldn't be worked out (no token, network, rate limit…): the Merge button's own wording. */
    fun planFailed(problem: MergeProblem): MergeDialog =
        dialogs.refusal(problem, 0).copy(title = "Couldn't check the PRs", link = board.issuesUrl())

    /** "Merging 2 of 5… waiting for checks". */
    fun progress(state: MergeAllState, cancelling: Boolean): String {
        val line = "Merging ${state.index + 1} of ${state.plan.size}… ${step(state.phase)}"
        return if (cancelling) "$line · stopping after this step" else line
    }

    fun step(phase: MergeAllPhase): String = when (phase) {
        MergeAllPhase.PREPARE, MergeAllPhase.UPDATE -> "updating"
        MergeAllPhase.WAIT -> "waiting for checks"
        MergeAllPhase.MERGE -> "merging"
    }

    /** How an ended run went, in one line. Null while it runs. */
    fun result(state: MergeAllState): String? = when (val end = state.end) {
        null -> null
        MergeAllEnd.Done -> "Merged all ${count(state.plan.size)}"
        MergeAllEnd.Cancelled -> "Merge all cancelled: merged ${state.merged.size} of ${state.plan.size}"
        is MergeAllEnd.Stopped -> "Stopped at ${name(state.plan[end.at])}: ${reason(end.stop)}. Merged ${state.merged.size} of ${state.plan.size}"
    }

    fun offer(n: Int) = "${count(n)} ready to merge"

    fun reason(stop: MergeAllStop): String {
        val detail = stop.detail?.let { TitleCleaner.clean(it, DETAIL_MAX) }?.takeIf { it.isNotEmpty() }
        fun with(text: String) = detail?.let { "$text ($it)" } ?: text
        return when (stop.kind) {
            Kind.CONFLICT -> with("has conflicts")
            Kind.CHECKS_FAILED -> with("checks failed")
            Kind.CHECKS_TIMEOUT -> with("checks still running after ${MergeAllMachine.CHECKS_TIMEOUT.toMinutes()} min")
            Kind.NO_CHECKS -> "no checks passed"
            Kind.PR_CHANGED -> "the PR got new commits"
            Kind.PR_CLOSED -> "the PR was closed"
            Kind.DRAFT -> "the PR became a draft"
            Kind.STACKED -> with("stacked on a branch that wasn't merged")
            Kind.NO_ACCESS -> "the token can't merge in this repo"
            Kind.NO_ACTIONS_ACCESS -> "the token can't read CI (needs Actions: Read-only)"
            Kind.RATE_LIMITED -> "rate-limited by GitHub"
            Kind.NO_TOKEN -> "the token was rejected"
            Kind.NETWORK -> "couldn't reach GitHub"
            Kind.ERROR -> with("unexpected error")
        }
    }

    private fun name(p: PlannedMerge) = "${p.pr.repo.name} #${p.pr.number}"

    private fun count(n: Int) = if (n == 1) "1 PR" else "$n PRs"

    private companion object {
        const val TITLE_MAX = 80
        const val DETAIL_MAX = 80
    }
}

/** The widget's Merge all line (#57) and its one button. */
data class MergeAllBanner(val text: String, val button: Button?) {
    enum class Button { MERGE_ALL, CANCEL, DISMISS }

    companion object {
        /**
         * A run that is going on or has ended shows on every tab; the offer only on Needs you.
         * [cancelling]: Cancel was tapped and the run stops after its current step.
         */
        fun of(state: MergeAllState?, cancelling: Boolean, board: Board?, onNeedsYou: Boolean, text: MergeAllText): MergeAllBanner? = when {
            state != null && state.running -> MergeAllBanner(text.progress(state, cancelling), Button.CANCEL.takeUnless { cancelling })
            state != null -> MergeAllBanner(text.result(state)!!, Button.DISMISS)
            onNeedsYou && MergeAllOffer.shows(board) -> MergeAllBanner(text.offer(MergeAllOffer.count(board)), Button.MERGE_ALL)
            else -> null
        }
    }
}
