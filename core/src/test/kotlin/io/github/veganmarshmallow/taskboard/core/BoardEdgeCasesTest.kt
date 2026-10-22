package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.BoardRow
import io.github.veganmarshmallow.taskboard.core.board.BoardRows
import io.github.veganmarshmallow.taskboard.core.board.NeedsYouReason
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.github.IssueJsonParser
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels.BLOCKED
import io.github.veganmarshmallow.taskboard.core.model.Labels.DONE
import io.github.veganmarshmallow.taskboard.core.model.Labels.IN_PROGRESS
import io.github.veganmarshmallow.taskboard.core.model.Labels.IN_REVIEW
import io.github.veganmarshmallow.taskboard.core.model.Labels.NEEDS_OWNER
import io.github.veganmarshmallow.taskboard.core.model.Labels.PRIORITY_HIGH
import io.github.veganmarshmallow.taskboard.core.model.Labels.READY_FOR_CLAUDE
import io.github.veganmarshmallow.taskboard.core.model.Labels.TODO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/** Reviewer-added edge cases for grouping, caps, the Done window and approve visibility. */
class BoardEdgeCasesTest {
    private val builder = BoardBuilder()
    private fun build(vararg issues: Issue) = builder.build(issues.toList(), NOW)
    private fun build(issues: List<Issue>) = builder.build(issues, NOW)

    @Test fun exactlyFiftyItemsHasNoMoreRowAndFiftyOneHasOne() {
        val fifty = build((2..51).map { issue(it, TODO) })
        assertEquals(50, fifty.section(Section.TODO).items.size)
        assertTrue(BoardRows.of(fifty, Section.TODO).none { it is BoardRow.More })

        val fiftyOne = build((2..52).map { issue(it, TODO) })
        assertEquals(50, fiftyOne.section(Section.TODO).items.size)
        assertEquals(51, fiftyOne.section(Section.TODO).totalCount)
        assertEquals(BoardRow.More(Section.TODO, 1), BoardRows.of(fiftyOne, Section.TODO).single { it is BoardRow.More })
    }

    @Test fun hugeBoardCapsEverySectionIndependently() {
        val issues = (2..2001).map { n ->
            val status = listOf(TODO, IN_PROGRESS, BLOCKED, IN_REVIEW)[n % 4]
            issue(n, status)
        }
        val board = build(issues)
        val open = Section.entries.filter { it != Section.DONE }
        // 500 per status; the 500 in review are in Needs you only, so the In review tab is empty.
        val listed = open.filter { it != Section.IN_REVIEW }
        listed.forEach { assertEquals(50, board.section(it).items.size) }
        listed.forEach { assertEquals(500, board.count(it)) }
        assertEquals(0, board.count(Section.IN_REVIEW))
        assertEquals(2000, open.sumOf { board.count(it) }) // every open issue counted exactly once
        listed.forEach { tab ->
            val rows = BoardRows.of(board, tab)
            assertEquals(1, rows.count { it is BoardRow.More })
            assertEquals(rows.size, rows.map { it.id }.toSet().size) // ids stay unique at scale
        }
    }

    @Test fun capOrdersByIssueNumberNotInputOrder() {
        val board = build((60 downTo 2).map { issue(it, TODO) })
        assertEquals((2..51).toList(), board.section(Section.TODO).items.map { it.number })
    }

    @Test fun emptyOpenBoardWithRecentDoneListsItOnlyInDone() {
        val board = build(issue(5, DONE, open = false, closedAt = NOW.minusSeconds(60)))
        assertTrue(board.isEmpty)
        assertEquals(listOf(BoardRow.Empty(Section.TODO)), BoardRows.of(board, Section.TODO))
        assertEquals(listOf(5), board.section(Section.DONE).items.map { it.number })
    }

    @Test fun onlyTheSummaryIssueMeansEmptyBoard() {
        val board = build(issue(1, NEEDS_OWNER, IN_REVIEW, title = "📋 Board"))
        assertTrue(board.isEmpty)
    }

    @Test fun summaryIssueAndPullRequestsNeverCountAsDone() {
        val board = build(
            issue(1, DONE, open = false, closedAt = NOW),
            issue(40, DONE, open = false, closedAt = NOW, pr = true),
            issue(41, NEEDS_OWNER, pr = true),
        )
        assertEquals(0, board.doneLast7Days)
        assertTrue(board.isEmpty)
    }

    @Test fun doneWindowBoundariesAcrossTimezoneOffsets() {
        // NOW is 2026-10-22T12:00Z. Israel is UTC+3 in October.
        val body = page(
            issueJson(10, DONE, state = "closed", closedAt = "2026-10-15T15:00:00+03:00"), // exactly 7 days: counts
            issueJson(11, DONE, state = "closed", closedAt = "2026-10-15T14:59:59.999+03:00"), // 1 ms past: no
            issueJson(12, DONE, state = "closed", closedAt = "2026-10-15T12:00:00.001Z"), // just inside: counts
            issueJson(13, DONE, state = "closed", closedAt = "2026-10-15T11:59:59Z"), // just outside: no
            issueJson(14, DONE, state = "closed", closedAt = "2026-10-22T14:59:00+03:00"), // a minute ago, local time
        )
        val board = build(IssueJsonParser.parsePage(body))
        assertEquals(3, board.doneLast7Days)
    }

    @Test fun customDoneWindowOfZeroCountsOnlyNowAndFuture() {
        val board = BoardBuilder(doneWindow = Duration.ZERO).build(
            listOf(
                issue(2, DONE, open = false, closedAt = NOW),
                issue(3, DONE, open = false, closedAt = NOW.minusMillis(1)),
                issue(4, DONE, open = false, closedAt = NOW.plusSeconds(5)),
            ),
            NOW,
        )
        assertEquals(2, board.doneLast7Days)
    }

    @Test fun conflictingStatusLabelsResolveByPrecedence() {
        val board = build(
            issue(2, TODO, DONE), // open, todo beats done: shown in Todo
            issue(3, IN_PROGRESS, BLOCKED), // blocked beats in-progress
            issue(4, TODO, IN_PROGRESS), // in-progress beats todo
            issue(5, BLOCKED, IN_REVIEW), // in-review beats blocked: Needs you (PR)
            issue(6, IN_REVIEW, NEEDS_OWNER), // needs-owner beats in-review: a question
        )
        assertEquals(listOf(2), board.section(Section.TODO).items.map { it.number })
        assertEquals(listOf(4), board.section(Section.IN_PROGRESS).items.map { it.number })
        assertEquals(listOf(3), board.section(Section.BLOCKED).items.map { it.number })
        assertEquals(emptyList<Int>(), board.section(Section.IN_REVIEW).items.map { it.number })
        val needsYou = board.section(Section.NEEDS_YOU).items.associate { it.number to it.needsYouReason }
        assertEquals(mapOf(5 to NeedsYouReason.REVIEW, 6 to NeedsYouReason.QUESTION), needsYou)
    }

    @Test fun mixedCaseAndPaddedLabelsFromJsonStillMatch() {
        val body = page(
            issueJson(2, "  STATUS: In-Progress ", "Priority: HIGH"),
            issueJson(3, "Needs-Owner"),
            issueJson(4, "Status: Todo", "READY-FOR-CLAUDE"),
        )
        val board = build(IssueJsonParser.parsePage(body))
        val inProgress = board.section(Section.IN_PROGRESS).items.single()
        assertEquals(2, inProgress.number)
        assertEquals(io.github.veganmarshmallow.taskboard.core.model.Priority.HIGH, inProgress.priority)
        assertEquals(listOf(3), board.section(Section.NEEDS_YOU).items.map { it.number })
        assertFalse(board.section(Section.TODO).items.single().canApprove)
        assertEquals(listOf(4), board.section(Section.TODO).items.map { it.number }) // 3 is a question: Needs you only
        assertFalse(board.section(Section.NEEDS_YOU).items.single().canApprove) // answer first
    }

    @Test fun similarButDifferentLabelsDoNotMatch() {
        // "status:todo" (no space) and "needs owner" are not the board's labels: the issue is an unlabelled Todo.
        val board = build(issue(2, "status:todo", "needs owner", "ready for claude"))
        val item = board.section(Section.TODO).items.single()
        assertTrue(item.canApprove)
        assertEquals(null, item.needsYouReason)
    }

    @Test fun approveVisibilityAcrossEveryCombination() {
        val board = build(
            issue(2, TODO), // yes
            issue(3), // unlabelled todo: yes
            issue(4, TODO, PRIORITY_HIGH), // yes
            issue(5, READY_FOR_CLAUDE), // unlabelled but already approved: no
            issue(6, TODO, READY_FOR_CLAUDE), // no
            issue(7, IN_PROGRESS), // no: already running
            issue(8, BLOCKED), // no
            issue(9, IN_REVIEW), // no
            issue(10, TODO, NEEDS_OWNER), // no: waiting on an answer
            issue(11, DONE, TODO), // todo wins over done, open: yes
        )
        val approvable = board.sections.flatMap { it.items }.filter { it.canApprove }.map { it.number }.distinct().sorted()
        assertEquals(listOf(2, 3, 4, 11), approvable)
    }

    @Test fun hebrewEmojiAndLongTitlesAreCleanedOnItems() {
        val hebrew = "לבנות ווידג׳ט לוח משימות לאנדרואיד עם כפתור אישור ותמיכה מלאה בעברית ".repeat(8)
        val board = build(issue(2, TODO, title = hebrew), issue(3, TODO, title = "👩‍💻 Fix ✅ the 🇮🇱 build"))
        val items = board.section(Section.TODO).items
        assertTrue(items[0].title.length <= 200)
        assertTrue(items[0].title.startsWith("לבנות ווידג׳ט"))
        assertTrue(items[0].title.endsWith("…"))
        assertEquals("👩‍💻 Fix ✅ the 🇮🇱 build", items[1].title)
        assertEquals("👩‍💻 Fix ✅ the 🇮🇱 build", items[1].displayText)
    }

    @Test fun closedIssueWithoutDoneNeverShowsAnywhere() {
        val board = build(issue(2, TODO, open = false, closedAt = NOW), issue(3, NEEDS_OWNER, open = false, closedAt = NOW))
        assertTrue(board.isEmpty)
        assertEquals(0, board.doneLast7Days)
    }
}
