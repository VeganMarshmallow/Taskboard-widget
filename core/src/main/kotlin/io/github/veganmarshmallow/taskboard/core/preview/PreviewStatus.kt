package io.github.veganmarshmallow.taskboard.core.preview

import io.github.veganmarshmallow.taskboard.core.board.NeedsYouReason
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.model.Status

/** The status line of the preview. */
enum class PreviewStatus(val text: String) {
    NEEDS_YOU("Needs you"),
    IN_REVIEW("In review"),
    IN_PROGRESS("In progress"),
    BLOCKED("Blocked"),

    /** Blocked with `needs-owner` (#34): listed under Blocked, but the owner has to answer. */
    BLOCKED_ON_YOU("Blocked: waiting on you"),
    TODO("Todo"),
    DONE("Done"),
    CLOSED("Closed");

    companion object {
        /**
         * From the issue's labels, with the board's rules: blocked stays blocked (waiting on you with
         * `needs-owner`), otherwise `needs-owner` wins, and no status label is Todo.
         */
        fun of(issue: Issue): PreviewStatus {
            val status = Status.from(issue.labels)
            if (!issue.isOpen) return if (status == Status.DONE) DONE else CLOSED
            val needsOwner = Labels.NEEDS_OWNER in issue.labels
            if (status == Status.BLOCKED) return if (needsOwner) BLOCKED_ON_YOU else BLOCKED
            if (needsOwner) return NEEDS_YOU
            return when (status) {
                Status.IN_REVIEW -> IN_REVIEW
                Status.BLOCKED -> BLOCKED
                Status.IN_PROGRESS -> IN_PROGRESS
                Status.TODO, null -> TODO
                Status.DONE -> DONE
            }
        }

        /** From where the widget's cached board placed the task (before the issue has loaded). */
        fun of(section: Section, reason: NeedsYouReason?): PreviewStatus = when (section) {
            Section.NEEDS_YOU -> if (reason == NeedsYouReason.REVIEW) IN_REVIEW else NEEDS_YOU
            Section.IN_PROGRESS -> IN_PROGRESS
            Section.BLOCKED -> if (reason == NeedsYouReason.QUESTION) BLOCKED_ON_YOU else BLOCKED
            Section.TODO -> TODO
            Section.DONE -> DONE
        }
    }
}
