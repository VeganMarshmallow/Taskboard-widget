package io.github.veganmarshmallow.taskboard.core.board

import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.model.Status

/**
 * Where an open issue shows up, and what it offers. The rules:
 *
 * - Status tab, by the winning `status:` label (precedence in-review > blocked > in-progress >
 *   todo > done, see [Status]). No status label → Todo (a freshly filed task). Only `status: done`
 *   but still open → no status tab (it is about to be closed).
 * - Also Needs you when it carries `needs-owner` (a QUESTION) or its status is in-review (a REVIEW).
 * - The Approve button shows only on Todo issues without `ready-for-claude` or `needs-owner`.
 *
 * Closed issues are never placed here; the Done tab is built from them separately.
 */
object Placement {
    fun sectionsOf(issue: Issue): Set<Section> {
        if (!issue.isOpen) return emptySet()
        val result = LinkedHashSet<Section>(2)
        if (needsYouReason(issue) != null) result.add(Section.NEEDS_YOU)
        statusSection(issue)?.let(result::add)
        return result
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

    private fun statusSection(issue: Issue): Section? = when (Status.from(issue.labels)) {
        Status.IN_REVIEW -> Section.IN_REVIEW
        Status.BLOCKED -> Section.BLOCKED
        Status.IN_PROGRESS -> Section.IN_PROGRESS
        Status.TODO, null -> Section.TODO
        Status.DONE -> null
    }
}
