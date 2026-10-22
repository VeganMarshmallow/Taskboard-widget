package io.github.veganmarshmallow.taskboard.core.preview

import io.github.veganmarshmallow.taskboard.core.error.BoardError

/**
 * Everything the preview activity renders, as an immutable value with pure transitions. The
 * activity shows the cached row at once ([initial]), starts loading, and folds results in.
 *
 * - [task] is null only while nothing is known (not cached and not loaded yet, or the load failed).
 * - [loading] is true while the issue is being (re)loaded.
 * - [error] is the latest failure of the issue load or of an approval; a stale [task] stays visible.
 * - [claudeLine] is the latest Claude comment; comment failures just leave it null.
 * - [approving] hides the Approve button while an approval runs; [justApproved] shows a confirmation.
 */
data class PreviewScreen(
    val number: Int,
    val task: TaskPreview?,
    val loading: Boolean,
    val error: PreviewError? = null,
    val claudeLine: String? = null,
    val approving: Boolean = false,
    val justApproved: Boolean = false,
) {
    val showApprove: Boolean get() = task?.canApprove == true && !approving && !loading

    /** Text for the summary area: the summary, "No description" once loaded, or nothing yet. */
    val summaryState: SummaryState
        get() = when {
            task?.summary != null -> SummaryState.Text(task.summary)
            task?.isFromIssue == true -> SummaryState.NoDescription
            else -> SummaryState.NotLoaded
        }

    fun loadStarted() = copy(loading = true, error = null)

    fun issueLoaded(details: IssueDetails) = copy(task = TaskPreview.fromIssue(details), loading = false, error = null)

    fun issueFailed(error: BoardError) = copy(loading = false, error = PreviewError.Load(error))

    fun commentsLoaded(comments: List<IssueComment>) = copy(claudeLine = ClaudeComments.latestLine(comments))

    fun approveStarted() = copy(approving = true, error = null, justApproved = false)

    /** The board said yes; hide the button now, the reload that follows brings the new labels. */
    fun approveSucceeded() = copy(
        approving = false,
        justApproved = true,
        task = task?.copy(canApprove = false),
    )

    fun approveFailed(error: BoardError) = copy(approving = false, error = PreviewError.Approve(error))

    companion object {
        fun initial(number: Int, cached: TaskPreview?) = PreviewScreen(number, cached, loading = true)
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
}
