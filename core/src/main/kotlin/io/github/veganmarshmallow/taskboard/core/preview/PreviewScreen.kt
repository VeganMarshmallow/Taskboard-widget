package io.github.veganmarshmallow.taskboard.core.preview

import io.github.veganmarshmallow.taskboard.core.error.BoardError

/**
 * Everything the preview activity renders, as an immutable value with pure transitions. The
 * activity shows the cached row at once ([initial]), starts loading, and folds results in.
 *
 * - [task] is null only while nothing is known (not cached and not loaded yet, or the load failed).
 * - [loading] is true while the issue is being (re)loaded.
 * - [error] is the latest failure of the issue load or of an approval; a stale [task] stays visible.
 * - [claudeComment] is the latest Claude comment in full (task #43); comment failures just leave it null.
 * - [approving] hides the Approve button while an approval runs; [justApproved] shows a confirmation.
 * - [justMerged] shows that the owner merged the task's PR from here (task #30) and hides Merge.
 */
data class PreviewScreen(
    val number: Int,
    val task: TaskPreview?,
    val loading: Boolean,
    val error: PreviewError? = null,
    val claudeComment: ClaudeMessage? = null,
    val approving: Boolean = false,
    val justApproved: Boolean = false,
    val justMerged: Boolean = false,
) {
    val showApprove: Boolean get() = task?.canApprove == true && !approving && !loading

    /** Merge is offered on a loaded or cached in-review task; the checks run when it is tapped. */
    val showMerge: Boolean get() = task?.canMerge == true && !loading && !justMerged

    /** The summary area: the summary, "No description" once loaded, "Loading…" while loading, else hidden. */
    val summaryState: SummaryState
        get() = when {
            task?.summary != null -> SummaryState.Text(task.summary)
            task?.isFromIssue == true -> SummaryState.NoDescription
            loading -> SummaryState.NotLoaded
            else -> SummaryState.Unavailable
        }

    fun loadStarted() = copy(loading = true, error = null)

    /**
     * Once approved here, a reload that still lacks `ready-for-claude` (a stale read right after the
     * write) must not bring the Approve button back.
     */
    fun issueLoaded(details: IssueDetails) = copy(
        task = TaskPreview.fromIssue(details)
            .let { if (justApproved) it.copy(canApprove = false) else it }
            // Claude moves the task to done on its next run; until then the issue still says in-review.
            .let { if (justMerged) it.copy(canMerge = false) else it },
        loading = false,
        error = null,
    )

    /** A deleted or transferred issue keeps its stale row for context, but can no longer be approved. */
    fun issueFailed(error: BoardError) = copy(loading = false, error = PreviewError.Load(error), task = taskAfter(error))

    fun commentsLoaded(comments: List<IssueComment>) = copy(claudeComment = ClaudeComments.latestMessage(comments))

    fun approveStarted() = copy(approving = true, error = null, justApproved = false)

    /** The board said yes; hide the button now, the reload that follows brings the new labels. */
    fun approveSucceeded() = copy(
        approving = false,
        justApproved = true,
        task = task?.copy(canApprove = false),
    )

    /** The PR was merged from the merge dialog; the reload that follows still shows in-review for a while. */
    fun mergeSucceeded() = copy(justMerged = true, error = null, task = task?.copy(canMerge = false))

    fun approveFailed(error: BoardError) = copy(approving = false, error = PreviewError.Approve(error), task = taskAfter(error))

    private fun taskAfter(error: BoardError): TaskPreview? =
        if (error is BoardError.IssueGone) task?.copy(canApprove = false, canMerge = false) else task

    companion object {
        /**
         * The first screen: the cached row while loading. [approvedEarlier] restores a confirmation
         * after the process was killed and the activity recreated, so Approve isn't offered again;
         * [mergedEarlier] does the same for Merge.
         */
        fun initial(number: Int, cached: TaskPreview?, approvedEarlier: Boolean = false, mergedEarlier: Boolean = false) = PreviewScreen(
            number,
            cached?.let { if (approvedEarlier) it.copy(canApprove = false) else it }
                ?.let { if (mergedEarlier) it.copy(canMerge = false) else it },
            loading = true,
            justApproved = approvedEarlier,
            justMerged = mergedEarlier,
        )
    }
}

/** Which step failed, so the message can say so. */
sealed class PreviewError {
    abstract val error: BoardError

    data class Load(override val error: BoardError) : PreviewError()
    data class Approve(override val error: BoardError) : PreviewError()
}

sealed class SummaryState {
    data class Text(val text: String) : SummaryState()
    data object NoDescription : SummaryState()
    data object NotLoaded : SummaryState()

    /** The load failed before any body arrived: show nothing rather than a stuck "Loading…". */
    data object Unavailable : SummaryState()
}
