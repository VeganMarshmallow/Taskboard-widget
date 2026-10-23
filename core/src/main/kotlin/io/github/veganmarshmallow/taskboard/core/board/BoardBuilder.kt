package io.github.veganmarshmallow.taskboard.core.board

import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.model.Priority
import java.time.Duration
import java.time.Instant

/**
 * Turns raw issues into a [Board]. The rules:
 *
 * - Issue #[summaryIssueNumber] (the "📋 Board" summary) and pull requests are dropped.
 * - Duplicate issue numbers (an issue in both the open and the done listing) are kept once,
 *   preferring the open copy.
 * - Each open issue goes to the one tab [Placement] picks: Needs you for questions and reviews,
 *   otherwise its status tab.
 * - Done = closed issues labelled `status: done` whose closed_at is no more than [doneWindow]
 *   before now (inclusive). Closed times slightly in the future (clock skew) also count. They are
 *   listed most recently closed first.
 * - Within an open tab: `priority: high` first, then normal, then `priority: low`; ties by issue
 *   number ascending (oldest first, the order Claude works them in).
 * - Each tab shows at most [maxPerSection] items; the true total is kept.
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

        val placed = tasks.filter { it.isOpen }.map { it to Placement.sectionsOf(it) }
        val done = tasks.filter { isRecentlyDone(it, now) }
            .sortedWith(compareByDescending<Issue> { it.closedAt }.thenByDescending { it.number })
            .map(::toItem)
        val sections = Section.entries.map { section ->
            val items = if (section == Section.DONE) done else placed
                .filter { section in it.second }
                .map { toItem(it.first) }
                .sortedWith(compareBy<BoardItem>({ it.priority.ordinal }, { it.number }))
            SectionContent(section, items.take(maxPerSection), items.size)
        }
        return Board(sections, now)
    }

    private fun toItem(issue: Issue) = BoardItem(
        number = issue.number,
        title = TitleCleaner.clean(issue.title),
        priority = Priority.from(issue.labels),
        canApprove = Placement.canApprove(issue),
        needsYouReason = Placement.needsYouReason(issue),
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
