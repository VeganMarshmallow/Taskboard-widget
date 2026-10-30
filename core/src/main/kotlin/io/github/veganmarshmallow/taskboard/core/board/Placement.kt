package io.github.veganmarshmallow.taskboard.core.board

import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.model.Status

/**
 * Where an open issue shows up, and what it offers. The rules:
 *
 * - Needs you when it carries `needs-owner` (a QUESTION) or its status is in-review (a REVIEW,
 *   whether or not a PR is linked: the owner has to look either way). Needs you is exclusive (the
 *   owner's decision on #10): such an item is listed and counted only there, as in the old widget.
 *   That is why there is no In review tab: it could never hold anything.
 * - Otherwise its status tab, by the winning `status:` label (precedence in-review > blocked >
 *   in-progress > todo > done, see [Status]). No status label → Todo (a freshly filed task). Only
 *   `status: done` but still open → no tab (it is about to be closed).
 * - The Approve button shows only on Todo issues without `ready-for-claude` or `needs-owner`.
 * - The Merge button (task #30) shows on open issues whose status is in-review, which are always
 *   in Needs you; also with `needs-owner`, since the PR is still waiting. Finding the PR and checking
 *   it can be merged happens only when the button is tapped.
 *
 * Closed issues are never placed here; the Done tab is built from them separately.
 */
object Placement {
    /** At most one tab: an issue is never listed (or counted) twice. */
    fun sectionsOf(issue: Issue): Set<Section> {
        if (!issue.isOpen) return emptySet()
        if (needsYouReason(issue) != null) return setOf(Section.NEEDS_YOU)
        return setOfNotNull(statusSection(issue))
    }

    fun needsYouReason(issue: Issue): NeedsYouReason? = when {
        !issue.isOpen -> null
        Labels.NEEDS_OWNER in issue.labels -> NeedsYouReason.QUESTION
        Status.from(issue.labels) == Status.IN_REVIEW -> NeedsYouReason.REVIEW
        else -> null
    }

    fun canApprove(issue: Issue): Boolean =
        issue.isOpen && statusSection(issue) == Section.TODO &&
            Labels.READY_FOR_CLAUDE !in issue.labels && Labels.NEEDS_OWNER !in issue.labels

    fun canMerge(issue: Issue): Boolean =
        issue.isOpen && !issue.isPullRequest && Status.from(issue.labels) == Status.IN_REVIEW

    private fun statusSection(issue: Issue): Section? = when (Status.from(issue.labels)) {
        Status.IN_REVIEW -> Section.NEEDS_YOU // already caught by needsYouReason; never dropped
        Status.BLOCKED -> Section.BLOCKED
        Status.IN_PROGRESS -> Section.IN_PROGRESS
        Status.TODO, null -> Section.TODO
        Status.DONE -> null
    }
}
