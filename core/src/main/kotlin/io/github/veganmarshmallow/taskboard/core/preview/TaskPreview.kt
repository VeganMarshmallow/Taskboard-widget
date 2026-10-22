package io.github.veganmarshmallow.taskboard.core.preview

import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.board.TitleCleaner
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.tags.TagPill
import io.github.veganmarshmallow.taskboard.core.tags.TagPills

/**
 * What the preview shows about a task. [summary] is null when it is not known yet (only the cached
 * board row is available) and [hasDescription] says whether a loaded body had any text.
 */
data class TaskPreview(
    val number: Int,
    val title: String,
    val status: PreviewStatus,
    val canApprove: Boolean,
    val summary: String? = null,
    val isFromIssue: Boolean = false,
    val tags: List<TagPill> = emptyList(),
) {
    val hasDescription: Boolean get() = summary != null

    companion object {
        /** From the widget's cached board, or null when the task is not on it. */
        fun fromBoard(board: Board?, number: Int): TaskPreview? {
            val content = board?.sections?.firstOrNull { s -> s.items.any { it.number == number } } ?: return null
            val item = content.items.first { it.number == number }
            return TaskPreview(number, item.title, PreviewStatus.of(content.section, item.needsYouReason), item.canApprove)
        }

        /** From a freshly loaded issue, with the board's Approve rule (open Todo without `ready-for-claude`). */
        fun fromIssue(details: IssueDetails, builder: BoardBuilder = BoardBuilder()): TaskPreview {
            val issue = details.issue
            val canApprove = issue.isOpen && !issue.isPullRequest &&
                builder.place(issue) == Section.TODO && Labels.READY_FOR_CLAUDE !in issue.labels
            return TaskPreview(
                number = issue.number,
                title = TitleCleaner.clean(issue.title, TITLE_MAX_LENGTH),
                status = PreviewStatus.of(issue),
                canApprove = canApprove,
                summary = TaskSummary.of(details.body),
                isFromIssue = true,
                tags = TagPills.of(issue.labels.associateWith { details.labelColors[it] }, details.body),
            )
        }

        /** The preview has room for a longer title than a widget row. */
        const val TITLE_MAX_LENGTH = 300
    }
}
