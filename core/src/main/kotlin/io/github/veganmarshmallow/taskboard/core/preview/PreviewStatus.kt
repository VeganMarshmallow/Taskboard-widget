package io.github.veganmarshmallow.taskboard.core.preview

import io.github.veganmarshmallow.taskboard.core.board.NeedsYouReason
import io.github.veganmarshmallow.taskboard.core.board.Placement
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.model.Issue
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
         * From the issue's labels, through the board's own [Placement] rules, so a loaded issue and
         * its cached row always agree (#34: blocked stays blocked, waiting on you with `needs-owner`).
         * Open with only `status: done` (about to be closed, on no tab) reads Done.
         */
        fun of(issue: Issue): PreviewStatus {
            if (!issue.isOpen) return if (Status.from(issue.labels) == Status.DONE) DONE else CLOSED
            val section = Placement.sectionsOf(issue).singleOrNull() ?: return DONE
            return of(section, Placement.needsYouReason(issue))
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
