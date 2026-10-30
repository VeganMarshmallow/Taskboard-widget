package io.github.veganmarshmallow.taskboard.core.merge

import io.github.veganmarshmallow.taskboard.core.board.TitleCleaner
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.error.ErrorMessages
import io.github.veganmarshmallow.taskboard.core.model.Repo

/** Builds each [MergeDialog] of the merge flow: checking, confirm, merging, and every refusal. */
class MergeDialogs(private val messages: ErrorMessages, private val board: Repo = Repo.BOARD) {
    fun checking(issue: Int) = MergeDialog(
        MergeDialog.Kind.PROGRESS, "Checking the PR…", listOf("Finding the PR for task #$issue and checking it can be merged."),
        primary = null, secondary = MergeButton.CANCEL, busy = true,
    )

    /** The confirmation: PR title, repo and number, and what merging does. */
    fun confirm(pr: PullRequest) = MergeDialog(
        MergeDialog.Kind.CONFIRM, "Merge PR #${pr.number}?",
        listOf(
            title(pr),
            "${pr.repo.fullName} #${pr.number}",
            "Into ${pr.baseRef} with a merge commit. Not a draft, no conflicts, all checks passed.",
        ),
        primary = MergeButton.MERGE, secondary = MergeButton.CANCEL, link = pr.ref.webUrl,
    )

    fun merging(pr: PullRequest) = MergeDialog(
        MergeDialog.Kind.PROGRESS, "Merging PR #${pr.number}…", listOf(title(pr), "${pr.repo.fullName} #${pr.number}"),
        primary = null, secondary = null, busy = true,
    )

    /** The toast after a merge. */
    fun merged(pr: PullRequest, issue: Int) =
        "Merged ${pr.repo.name} PR #${pr.number} into ${pr.baseRef}. Claude closes task #$issue on its next run"

    fun refusal(problem: MergeProblem, issue: Int): MergeDialog {
        val pr = problem.pr
        val link = pr?.ref?.webUrl ?: board.issueUrl(issue)
        fun refuse(title: String, vararg lines: String) =
            MergeDialog(MergeDialog.Kind.REFUSAL, title, lines.toList(), MergeButton.OPEN_GITHUB, MergeButton.CLOSE, link)
        return when (problem) {
            MergeProblem.NotFound -> refuse(
                "No PR found",
                "Couldn't find an open PR for task #$issue. Claude links its PR in a comment on the task, " +
                    "and the PR says \"Closes ${board.fullName}#$issue\".",
            )
            is MergeProblem.Ambiguous -> refuse(
                "Several open PRs",
                "Task #$issue has ${problem.candidates.size} open PRs (${problem.candidates.joinToString { "${it.repo.name} #${it.number}" }}) " +
                    "and none is clearly the one to merge. Pick it on GitHub.",
            )
            is MergeProblem.NotLinked -> refuse(
                "Not clearly this task's PR",
                "${problem.candidates.joinToString { "${it.repo.name} #${it.number}" }} is linked from task #$issue, but its " +
                    "description doesn't say \"Closes ${board.fullName}#$issue\", so it isn't merged from here. Check it on GitHub.",
            )
            is MergeProblem.AlreadyMerged -> refuse("Already merged", "PR #${problem.pr.number} is already merged. Claude closes the task on its next run.")
            is MergeProblem.Closed -> refuse("PR closed", "PR #${problem.pr.number} was closed without merging.")
            is MergeProblem.Draft -> refuse("Still a draft", "PR #${problem.pr.number} is a draft: it hasn't passed Claude's review yet.")
            is MergeProblem.Stacked -> refuse(
                "Merge another PR first",
                "This PR is stacked on ${problem.pr.baseRef}; " +
                    (problem.blocker?.let { "merge #$it first." } ?: "merge that branch's PR first."),
                "Merging it now would put it on that branch instead of ${problem.pr.defaultBranch ?: "the default branch"}.",
            )
            is MergeProblem.Conflicts -> refuse(conflictsTitle(problem.detail), *listOfNotNull(
                "PR #${problem.pr.number} can't be merged as it is.",
                problem.detail?.let { "GitHub says: $it" },
            ).toTypedArray())
            is MergeProblem.MergeabilityUnknown -> refuse("Not ready yet", "GitHub is still working out whether PR #${problem.pr.number} can be merged. Try again in a minute.")
            is MergeProblem.ChecksRunning -> refuse("Checks still running", "Wait for ${names(problem.names)} to finish, then try again.")
            is MergeProblem.ChecksFailed -> refuse("Checks failed", "${names(problem.names).replaceFirstChar { it.uppercase() }} failed on PR #${problem.pr.number}.")
            is MergeProblem.NoChecks -> refuse("No checks passed", "No workflow run has passed on PR #${problem.pr.number} (none reported yet, or all skipped).")
            is MergeProblem.HeadChanged -> refuse("PR changed; check again", "PR #${problem.pr.number} got new commits after it was checked. Nothing was merged.")
            is MergeProblem.NoRepoAccess -> noAccess(problem.repo)
            is MergeProblem.NoActionsAccess -> noActionsAccess(problem.repo)
            is MergeProblem.Failed -> failed(problem.error, link)
        }
    }

    /**
     * A refusal after the merge request was sent. When no clear answer came back (network,
     * timeout, server error), the PR may have merged anyway, so the dialog says to look.
     */
    fun mergeFailed(problem: MergeProblem, issue: Int): MergeDialog {
        val dialog = refusal(problem, issue)
        val unsure = problem is MergeProblem.Failed && !isDefiniteAnswer(problem.error)
        return if (unsure) dialog.copy(lines = dialog.lines + "It may have merged anyway: check on GitHub before trying again.") else dialog
    }

    /**
     * Errors where GitHub clearly answered "no" before merging anything. Everything else (network,
     * timeout, server errors, an unreadable answer, and any error kind added later) may hide a merge
     * that went through, so the dialog says to look first.
     */
    private fun isDefiniteAnswer(error: BoardError) =
        error is BoardError.RateLimited || error is BoardError.BadToken || error == BoardError.NoToken ||
            error == BoardError.NoAccess || error is BoardError.IssueGone

    /** 405 also covers branch protection ("Required status check ... is expected"), which is not a conflict. */
    private fun conflictsTitle(detail: String?) =
        if (detail == null || detail.contains("conflict", ignoreCase = true) || detail.contains("not mergeable", ignoreCase = true)) {
            "Has conflicts"
        } else {
            "GitHub refused the merge"
        }

    /** Precise steps: the token needs the project repo and three permissions. Editing keeps the same token. */
    private fun noAccess(repo: Repo) = MergeDialog(
        MergeDialog.Kind.REFUSAL, "Token can't merge here",
        listOf(
            "The token can't reach ${repo.fullName}'s pull requests. On GitHub, edit the token:",
            "1. Repository access: add ${repo.name}",
            "2. Permissions: $PERMISSIONS",
            "3. Save. The token itself doesn't change, so nothing to paste again.",
        ),
        primary = MergeButton.EDIT_TOKEN, secondary = MergeButton.CLOSE, link = TOKEN_SETTINGS,
    )

    /** The PR was readable but its CI result wasn't: only the Actions permission is missing. */
    private fun noActionsAccess(repo: Repo) = MergeDialog(
        MergeDialog.Kind.REFUSAL, "Token can't read CI",
        listOf(
            "The token can't read ${repo.fullName}'s GitHub Actions runs, so the checks can't be confirmed. On GitHub, edit the token:",
            "1. Repository access: keep ${repo.name}",
            "2. Permissions: add Actions: Read-only (all of them: $PERMISSIONS)",
            "3. Save. The token itself doesn't change, so nothing to paste again.",
        ),
        primary = MergeButton.EDIT_TOKEN, secondary = MergeButton.CLOSE, link = TOKEN_SETTINGS,
    )

    private fun failed(error: BoardError, link: String) = MergeDialog(
        MergeDialog.Kind.REFUSAL, "Couldn't merge", listOf(messages.message(error)),
        primary = if (messages.opensSetup(error)) MergeButton.SET_UP else MergeButton.OPEN_GITHUB,
        secondary = MergeButton.CLOSE, link = link,
    )

    private fun title(pr: PullRequest) =
        if (TitleCleaner.sanitize(pr.title).isEmpty()) "(untitled PR)" else TitleCleaner.clean(pr.title, TITLE_MAX)

    private fun names(all: List<String>): String {
        val shown = all.take(MAX_NAMES).joinToString(", ") { TitleCleaner.clean(it, NAME_MAX) }
        val more = all.size - MAX_NAMES
        val label = if (all.size == 1) "workflow" else "workflows"
        return if (more > 0) "$label $shown and $more more" else "$label $shown"
    }

    companion object {
        const val TOKEN_SETTINGS = "https://github.com/settings/personal-access-tokens"

        /** What merging needs on a fine-grained token (task #38: Actions replaced Checks, which such tokens lack). */
        const val PERMISSIONS = "Contents: Read and write, Pull requests: Read and write, Actions: Read-only"
        private const val TITLE_MAX = 200
        private const val NAME_MAX = 60
        private const val MAX_NAMES = 3
    }
}
