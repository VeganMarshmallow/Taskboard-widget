package io.github.veganmarshmallow.taskboard.core.board

import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.model.Priority
import io.github.veganmarshmallow.taskboard.core.model.Status
import java.time.Duration
import java.time.Instant

/**
 * Turns raw issues into a [Board]. The rules:
 *
 * - Issue #[summaryIssueNumber] (the "📋 Board" summary) and pull requests are dropped.
 * - Duplicate issue numbers (an issue in both the open and the done listing) are kept once,
 *   preferring the open copy.
 * - Open issues go to exactly one section:
 *   1. `needs-owner` → Needs you (QUESTION), whatever its status.
 *   2. else `status: in-review` → Needs you (REVIEW).
 *   3. else by status, with precedence in-review > blocked > in-progress > todo > done when an
 *      issue carries several status labels (see [Status]).
 *   4. No status label → Todo (a freshly filed task that has not been labelled yet).
 *   5. Only `status: done` but still open → not shown (it is about to be closed).
 * - Done = closed issues labelled `status: done` whose closed_at is no more than [doneWindow]
 *   before now (inclusive). Closed times slightly in the future (clock skew) also count.
 * - Within a section: `priority: high` first, then normal, then `priority: low`; ties by issue
 *   number ascending (oldest first, the order Claude works them in).
 * - Each section shows at most [maxPerSection] items; the true total is kept.
 * - The Approve button shows only on Todo items that lack `ready-for-claude`.
 */
class BoardBuilder(
    private val maxPerSection: Int = DEFAULT_MAX_PER_SECTION,
    private val doneWindow: Duration = Duration.ofDays(7),
    private val summaryIssueNumber: Int = 1,
) {
    init {
        require(maxPerSection >= 1) { "maxPerSection must be at least 1" }
        require(!doneWindow.isNegative) { "doneWindow must not be negative" }
    }

    fun build(issues: List<Issue>, now: Instant): Board {
        val tasks = issues
            .filter { !it.isPullRequest && it.number != summaryIssueNumber }
            .sortedByDescending { it.isOpen } // open copies first, so distinctBy keeps them
            .distinctBy { it.number }

        val placed = tasks.filter { it.isOpen }.mapNotNull { issue -> place(issue)?.let { issue to it } }
        val sections = Section.entries.map { section ->
            val items = placed.filter { it.second == section }
                .map { (issue, sec) -> toItem(issue, sec) }
                .sortedWith(compareBy<BoardItem>({ it.priority.ordinal }, { it.number }))
            SectionContent(section, items.take(maxPerSection), items.size)
        }
        val doneCount = tasks.count { isRecentlyDone(it, now) }
        return Board(sections, doneCount, now)
    }

    /** The section an open issue belongs in, or null when it should not be shown. */
    fun place(issue: Issue): Section? {
        if (Labels.NEEDS_OWNER in issue.labels) return Section.NEEDS_YOU
        return when (Status.from(issue.labels)) {
            Status.IN_REVIEW -> Section.NEEDS_YOU
            Status.BLOCKED -> Section.BLOCKED
            Status.IN_PROGRESS -> Section.IN_PROGRESS
            Status.TODO, null -> Section.TODO
            Status.DONE -> null
        }
    }

    private fun toItem(issue: Issue, section: Section) = BoardItem(
        number = issue.number,
        title = TitleCleaner.clean(issue.title),
        priority = Priority.from(issue.labels),
        canApprove = section == Section.TODO && Labels.READY_FOR_CLAUDE !in issue.labels,
        needsYouReason = when {
            section != Section.NEEDS_YOU -> null
            Labels.NEEDS_OWNER in issue.labels -> NeedsYouReason.QUESTION
            else -> NeedsYouReason.REVIEW
        },
    )

    private fun isRecentlyDone(issue: Issue, now: Instant): Boolean {
        if (issue.isOpen || Labels.DONE !in issue.labels) return false
        val closedAt = issue.closedAt ?: return false
        return !closedAt.isBefore(now.minus(doneWindow))
    }

    companion object {
        const val DEFAULT_MAX_PER_SECTION = 50
    }
}
