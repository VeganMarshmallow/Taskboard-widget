package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.NeedsYouReason
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.model.Labels.BLOCKED
import io.github.veganmarshmallow.taskboard.core.model.Labels.DONE
import io.github.veganmarshmallow.taskboard.core.model.Labels.IN_PROGRESS
import io.github.veganmarshmallow.taskboard.core.model.Labels.IN_REVIEW
import io.github.veganmarshmallow.taskboard.core.model.Labels.NEEDS_OWNER
import io.github.veganmarshmallow.taskboard.core.model.Labels.PRIORITY_HIGH
import io.github.veganmarshmallow.taskboard.core.model.Labels.PRIORITY_LOW
import io.github.veganmarshmallow.taskboard.core.model.Labels.READY_FOR_CLAUDE
import io.github.veganmarshmallow.taskboard.core.model.Labels.TODO
import io.github.veganmarshmallow.taskboard.core.model.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class BoardBuilderTest {
    private val builder = BoardBuilder()

    private fun build(vararg issues: io.github.veganmarshmallow.taskboard.core.model.Issue) = builder.build(issues.toList(), NOW)
    private fun Board.numbers(section: Section) = section(section).items.map { it.number }
    private fun Board.allShown() = sections.filter { it.section != Section.DONE }.flatMap { s -> s.items.map { it.number } }.distinct()
    private val statusTabSections = listOf(Section.TODO, Section.IN_PROGRESS, Section.BLOCKED)
    private fun Board.statusTabs() = statusTabSections.flatMap { numbers(it) }

    @Test fun groupsEachStatusIntoItsSection() {
        val board = build(
            issue(2, TODO), issue(3, IN_PROGRESS), issue(4, BLOCKED), issue(5, IN_REVIEW),
            issue(6, BLOCKED, NEEDS_OWNER),
        )
        assertEquals(listOf(5, 6), board.numbers(Section.NEEDS_YOU))
        assertEquals(listOf(3), board.numbers(Section.IN_PROGRESS))
        assertEquals(listOf(4), board.numbers(Section.BLOCKED)) // 6 needs you, so it is only there
        assertEquals(listOf(2), board.numbers(Section.TODO))
    }

    @Test fun sectionsAlwaysPresentInDisplayOrder() {
        assertEquals(Section.entries.toList(), build().sections.map { it.section })
        assertEquals(listOf("Needs you", "Todo", "In progress", "Blocked", "Done"), Section.entries.map { it.title })
    }

    @Test fun needsOwnerIsAQuestionWhateverItsStatusAndLeavesItsStatusTab() {
        val statuses = listOf(TODO, IN_PROGRESS, BLOCKED, IN_REVIEW)
        val issues = statuses.mapIndexed { i, s -> issue(10 + i, s, NEEDS_OWNER) } + issue(20, NEEDS_OWNER)
        val board = builder.build(issues, NOW)
        assertEquals(listOf(10, 11, 12, 13, 20), board.numbers(Section.NEEDS_YOU))
        assertTrue(board.section(Section.NEEDS_YOU).items.all { it.needsYouReason == NeedsYouReason.QUESTION })
        assertEquals(emptyList<Int>(), board.statusTabs()) // Needs you is exclusive
        assertEquals(5, board.count(Section.NEEDS_YOU))
        statusTabSections.forEach { assertEquals("$it", 0, board.count(it)) }
    }

    @Test fun inReviewGoesToNeedsYouWithReviewReason() {
        val item = build(issue(7, IN_REVIEW)).section(Section.NEEDS_YOU).items.single()
        assertEquals(NeedsYouReason.REVIEW, item.needsYouReason)
    }

    @Test fun blockedWithoutNeedsOwnerStaysBlocked() {
        val board = build(issue(4, BLOCKED))
        assertEquals(listOf(4), board.numbers(Section.BLOCKED))
        assertEquals(0, board.section(Section.NEEDS_YOU).totalCount)
        assertNull(board.section(Section.BLOCKED).items.single().needsYouReason)
    }

    @Test fun excludesSummaryIssueAndPullRequests() {
        val board = build(
            issue(1, TODO, title = "📋 Board"), issue(1, NEEDS_OWNER),
            issue(8, IN_REVIEW, pr = true), issue(9, TODO, pr = true),
            issue(10, DONE, open = false, closedAt = NOW, pr = true),
            issue(2, TODO),
        )
        assertEquals(listOf(2), board.allShown())
        assertEquals(0, board.doneLast7Days)
    }

    @Test fun summaryIssueNumberIsConfigurable() {
        val board = BoardBuilder(summaryIssueNumber = 99).build(listOf(issue(1, TODO), issue(99, TODO)), NOW)
        assertEquals(listOf(1), board.allShown())
    }

    @Test fun priorityOrderingWithinSectionThenOldestFirst() {
        val board = build(
            issue(30, TODO, PRIORITY_LOW), issue(12, TODO), issue(25, TODO, PRIORITY_HIGH),
            issue(5, TODO, PRIORITY_LOW), issue(40, TODO, PRIORITY_HIGH), issue(3, TODO),
        )
        assertEquals(listOf(25, 40, 3, 12, 5, 30), board.numbers(Section.TODO))
    }

    @Test fun priorityAppliesInNeedsYouAcrossReasons() {
        val board = build(issue(3, IN_REVIEW), issue(9, NEEDS_OWNER, PRIORITY_HIGH), issue(2, IN_REVIEW, PRIORITY_LOW))
        assertEquals(listOf(9, 3, 2), board.numbers(Section.NEEDS_YOU))
    }

    @Test fun bothPriorityLabelsMeansHigh() {
        val item = build(issue(3, TODO, PRIORITY_LOW, PRIORITY_HIGH)).section(Section.TODO).items.single()
        assertEquals(Priority.HIGH, item.priority)
    }

    @Test fun labelMatchingIsCaseInsensitive() {
        val board = builder.build(
            listOf(issue(3, "Status: In-Progress"), issue(4, "NEEDS-OWNER"), issue(5, "STATUS: TODO", "Priority: HIGH")),
            NOW,
        )
        assertEquals(listOf(3), board.numbers(Section.IN_PROGRESS))
        assertEquals(listOf(4), board.numbers(Section.NEEDS_YOU))
        assertEquals(Priority.HIGH, board.section(Section.TODO).items.single { it.number == 5 }.priority)
        assertEquals(listOf(5), board.numbers(Section.TODO)) // 4 is a question: Needs you only
    }

    @Test fun issueWithNoLabelsIsTodoAndApprovable() {
        val item = build(issue(3)).section(Section.TODO).items.single()
        assertEquals(3, item.number)
        assertTrue(item.canApprove)
        assertEquals(Priority.NORMAL, item.priority)
    }

    @Test fun multipleStatusLabelsUseFixedPrecedence() {
        val board = build(
            issue(2, TODO, IN_PROGRESS),            // in-progress beats todo
            issue(3, IN_PROGRESS, BLOCKED),         // blocked beats in-progress
            issue(4, BLOCKED, IN_REVIEW),           // in-review beats blocked
            issue(5, TODO, DONE),                   // todo beats done (still open)
            issue(6, TODO, IN_PROGRESS, BLOCKED, IN_REVIEW, DONE),
        )
        assertEquals(listOf(4, 6), board.numbers(Section.NEEDS_YOU))
        assertEquals(listOf(3), board.numbers(Section.BLOCKED))
        assertEquals(listOf(2), board.numbers(Section.IN_PROGRESS))
        assertEquals(listOf(5), board.numbers(Section.TODO))
        assertEquals(board.statusTabs().size, board.statusTabs().toSet().size)
    }

    @Test fun openIssueOnlyMarkedDoneIsHidden() {
        val board = build(issue(3, DONE))
        assertTrue(board.isEmpty)
        assertEquals(0, board.doneLast7Days) // not closed, so not counted either
    }

    @Test fun approveVisibleOnlyForTodoWithoutReadyLabel() {
        val board = build(
            issue(2, TODO), issue(3, TODO, READY_FOR_CLAUDE), issue(4, IN_PROGRESS), issue(5, BLOCKED),
            issue(6, IN_REVIEW), issue(7, TODO, NEEDS_OWNER),
        )
        val approvable = board.sections.flatMap { it.items }.filter { it.canApprove }.map { it.number }.distinct()
        assertEquals(listOf(2), approvable)
    }

    @Test fun doneCountsWithinSevenDaysInclusiveOfBoundary() {
        val week = Duration.ofDays(7)
        val board = build(
            issue(10, DONE, open = false, closedAt = NOW),
            issue(11, DONE, open = false, closedAt = NOW.minus(week)),                 // exactly 7 days: counts
            issue(12, DONE, open = false, closedAt = NOW.minus(week).plusMillis(1)),
            issue(13, DONE, open = false, closedAt = NOW.minus(week).minusMillis(1)),  // just outside
            issue(14, DONE, open = false, closedAt = NOW.minus(Duration.ofDays(30))),
            issue(15, DONE, open = false, closedAt = NOW.plusSeconds(90)),             // clock skew
        )
        assertEquals(4, board.doneLast7Days)
        assertTrue(board.isEmpty)
    }

    @Test fun doneIgnoresClosedWithoutDoneLabelOrWithoutClosedAt() {
        val board = build(
            issue(10, TODO, open = false, closedAt = NOW),  // closed as not planned etc.
            issue(11, open = false, closedAt = NOW),
            issue(12, DONE, open = false, closedAt = null),
        )
        assertEquals(0, board.doneLast7Days)
    }

    @Test fun duplicateNumbersAreCountedOnceAndOpenCopyWins() {
        val board = build(
            issue(10, DONE, open = false, closedAt = NOW),
            issue(10, DONE, open = false, closedAt = NOW),
            issue(11, DONE, open = false, closedAt = NOW), issue(11, TODO),
            issue(12, TODO), issue(12, TODO),
        )
        assertEquals(1, board.doneLast7Days)
        assertEquals(listOf(11, 12), board.numbers(Section.TODO))
    }

    @Test fun capsItemsPerSectionButKeepsTotals() {
        val many = (2..200).map { issue(it, TODO) }
        val board = builder.build(many, NOW)
        val todo = board.section(Section.TODO)
        assertEquals(50, todo.items.size)
        assertEquals(199, todo.totalCount)
        assertEquals(149, todo.hiddenCount)
        assertEquals((2..51).toList(), todo.items.map { it.number })
    }

    @Test fun capKeepsHighPriorityItems() {
        val many = (2..80).map { issue(it, TODO) } + issue(500, TODO, PRIORITY_HIGH)
        val todo = BoardBuilder(maxPerSection = 3).build(many, NOW).section(Section.TODO)
        assertEquals(listOf(500, 2, 3), todo.items.map { it.number })
    }

    @Test fun emptyBoard() {
        val board = build()
        assertTrue(board.isEmpty)
        assertEquals(0, board.doneLast7Days)
        assertEquals(NOW, board.fetchedAt)
        assertFalse(build(issue(2, TODO)).isEmpty)
    }

    @Test fun displayTextAndCleanedTitle() {
        val item = build(issue(42, TODO, title = "  Fix\n\tthe   thing  ")).section(Section.TODO).items.single()
        assertEquals("Fix the thing", item.title)
        assertEquals("Fix the thing", item.displayText)
        assertEquals(42, item.number)
    }

    @Test fun veryLongAndEmojiTitlesAreKeptSafe() {
        val long = "🚀".repeat(500)
        val item = build(issue(2, TODO, title = long)).section(Section.TODO).items.single()
        assertTrue(item.title.length <= 200)
        assertTrue(item.title.endsWith("…"))
        assertFalse(Character.isHighSurrogate(item.title[item.title.length - 2]))
    }

    @Test fun rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException::class.java) { BoardBuilder(maxPerSection = 0) }
        assertThrows(IllegalArgumentException::class.java) { BoardBuilder(doneWindow = Duration.ofDays(-1)) }
    }
}
