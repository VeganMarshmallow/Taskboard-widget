package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.BoardRow
import io.github.veganmarshmallow.taskboard.core.board.BoardRows
import io.github.veganmarshmallow.taskboard.core.board.NeedsYouReason
import io.github.veganmarshmallow.taskboard.core.board.Placement
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels.BLOCKED
import io.github.veganmarshmallow.taskboard.core.model.Labels.DONE
import io.github.veganmarshmallow.taskboard.core.model.Labels.IN_PROGRESS
import io.github.veganmarshmallow.taskboard.core.model.Labels.IN_REVIEW
import io.github.veganmarshmallow.taskboard.core.model.Labels.NEEDS_OWNER
import io.github.veganmarshmallow.taskboard.core.model.Labels.PRIORITY_HIGH
import io.github.veganmarshmallow.taskboard.core.model.Labels.READY_FOR_CLAUDE
import io.github.veganmarshmallow.taskboard.core.model.Labels.TODO
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetStateCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/** What each of the six tabs holds: placement, overlap with Needs you, the Done list, and counts. */
class TabContentsTest {
    private fun build(vararg issues: Issue): Board = BoardBuilder().build(issues.toList(), NOW)
    private fun Board.numbers(s: Section) = section(s).items.map { it.number }
    private fun done(n: Int, agoHours: Long, vararg labels: String = arrayOf(DONE)) =
        issue(n, *labels, open = false, closedAt = NOW.minus(Duration.ofHours(agoHours)))

    @Test fun everyStatusHasItsOwnTab() {
        val board = build(issue(2, TODO), issue(3, IN_PROGRESS), issue(4, BLOCKED), issue(5, IN_REVIEW), done(6, 1))
        assertEquals(listOf(2), board.numbers(Section.TODO))
        assertEquals(listOf(3), board.numbers(Section.IN_PROGRESS))
        assertEquals(listOf(4), board.numbers(Section.BLOCKED))
        assertEquals(listOf(5), board.numbers(Section.IN_REVIEW))
        assertEquals(listOf(5), board.numbers(Section.NEEDS_YOU))
        assertEquals(listOf(6), board.numbers(Section.DONE))
    }

    @Test fun needsYouOverlapsStatusTabsButEachIssueHasOneStatusTab() {
        val board = build(issue(2, BLOCKED, NEEDS_OWNER), issue(3, IN_REVIEW), issue(4, IN_REVIEW, NEEDS_OWNER))
        assertEquals(listOf(2, 3, 4), board.numbers(Section.NEEDS_YOU))
        assertEquals(listOf(2), board.numbers(Section.BLOCKED))
        assertEquals(listOf(3, 4), board.numbers(Section.IN_REVIEW))
        val reasons = board.section(Section.NEEDS_YOU).items.associate { it.number to it.needsYouReason }
        assertEquals(mapOf(2 to NeedsYouReason.QUESTION, 3 to NeedsYouReason.REVIEW, 4 to NeedsYouReason.QUESTION), reasons)
        // The same item carries the same badge reason in its status tab.
        assertEquals(NeedsYouReason.QUESTION, board.section(Section.BLOCKED).items.single().needsYouReason)
    }

    @Test fun placementRules() {
        assertEquals(setOf(Section.TODO), Placement.sectionsOf(issue(2)))
        assertEquals(setOf(Section.NEEDS_YOU, Section.TODO), Placement.sectionsOf(issue(2, NEEDS_OWNER)))
        assertEquals(setOf(Section.NEEDS_YOU, Section.IN_REVIEW), Placement.sectionsOf(issue(2, IN_REVIEW)))
        assertEquals(emptySet<Section>(), Placement.sectionsOf(issue(2, DONE))) // open but done: about to close
        assertEquals(setOf(Section.NEEDS_YOU), Placement.sectionsOf(issue(2, DONE, NEEDS_OWNER)))
        assertEquals(emptySet<Section>(), Placement.sectionsOf(issue(2, NEEDS_OWNER, open = false)))
        assertNull(Placement.needsYouReason(issue(2, IN_REVIEW, open = false)))
        assertFalse(Placement.canApprove(issue(2, open = false)))
    }

    @Test fun approveOnlyInTodoAndNeverForQuestions() {
        val board = build(issue(2, TODO), issue(3, TODO, NEEDS_OWNER), issue(4, TODO, READY_FOR_CLAUDE), issue(5, IN_REVIEW))
        assertEquals(listOf(2), board.section(Section.TODO).items.filter { it.canApprove }.map { it.number })
        assertTrue(board.section(Section.NEEDS_YOU).items.none { it.canApprove })
    }

    @Test fun doneListsRecentlyClosedNewestFirst() {
        val board = build(done(10, 100), done(11, 1), done(12, 48), done(13, 48), done(14, 24 * 8), done(15, 3, TODO))
        assertEquals(listOf(11, 13, 12, 10), board.numbers(Section.DONE)) // ties: higher number first
        assertEquals(4, board.doneLast7Days)
        assertTrue(board.section(Section.DONE).items.none { it.canApprove || it.needsYouReason != null })
    }

    @Test fun doneTitlesAreCleanedAndPriorityKept() {
        val board = build(issue(9, DONE, PRIORITY_HIGH, title = "  ‮סיום\n משימה 🚀 ", open = false, closedAt = NOW))
        val item = board.section(Section.DONE).items.single()
        assertEquals("סיום משימה 🚀", item.title)
        assertEquals(io.github.veganmarshmallow.taskboard.core.model.Priority.HIGH, item.priority)
    }

    @Test fun reopenedIssueShowsOpenNotDone() {
        val board = build(done(7, 2), issue(7, IN_PROGRESS))
        assertEquals(listOf(7), board.numbers(Section.IN_PROGRESS))
        assertEquals(emptyList<Int>(), board.numbers(Section.DONE))
    }

    @Test fun doneIsCappedWithAMoreRowThatOpensTheDoneSearch() {
        val board = BoardBuilder(maxPerSection = 5).build((2..40).map { done(it, it.toLong()) }, NOW)
        assertEquals(39, board.count(Section.DONE))
        assertEquals((2..6).toList(), board.numbers(Section.DONE))
        val rows = BoardRows.of(board, Section.DONE)
        assertEquals(BoardRow.More(Section.DONE, 34), rows.last())
    }

    @Test fun emptyTabsEachGetTheirOwnEmptyRow() {
        val board = build()
        val empties = Section.entries.map { BoardRows.of(board, it).single() }
        assertEquals(Section.entries.map { BoardRow.Empty(it) }, empties)
        assertEquals(empties.size, empties.map { it.id }.toSet().size)
    }

    @Test fun isEmptyIgnoresDone() {
        assertTrue(build(done(3, 1)).isEmpty)
        assertFalse(build(issue(3, BLOCKED)).isEmpty)
    }

    @Test fun cacheRoundTripsDoneItemsAndSixTabs() {
        val board = build(issue(2, IN_REVIEW, NEEDS_OWNER), issue(3, BLOCKED), done(4, 5), done(5, 6))
        val state = WidgetState(board, null, NOW)
        val decoded = WidgetStateCodec.decode(WidgetStateCodec.encode(state))
        assertEquals(state, decoded)
        assertEquals(listOf(4, 5), decoded.board!!.numbers(Section.DONE))
    }

    @Test fun cacheFromTheOldFourSectionVersionIsDiscarded() {
        // v1 had no Done list; reading it would show an empty Done tab with a non-zero count, so start fresh.
        val v1 = """{"v":1,"board":{"fetchedAt":0,"done":3,"sections":[{"section":"TODO","total":0,"items":[]}]},"error":null,"attemptedAt":null}"""
        assertEquals(WidgetState.INITIAL, WidgetStateCodec.decode(v1))
    }

    @Test fun hugeBoardKeepsTheCacheSmallWithSixTabs() {
        val open = (2..2001).map { n ->
            val status = listOf(TODO, IN_PROGRESS, BLOCKED, IN_REVIEW)[n % 4]
            val labels = if (n % 7 == 0) arrayOf(status, NEEDS_OWNER) else arrayOf(status)
            issue(n, *labels, title = "משימה $n " + "x".repeat(150))
        }
        val closed = (3000..3500).map { done(it, 1, DONE) }
        val state = WidgetState(BoardBuilder().build(open + closed, NOW), null, NOW)
        val text = WidgetStateCodec.encode(state)
        assertTrue("six capped tabs: ${text.length}", text.length < 80_000)
        assertEquals(state, WidgetStateCodec.decode(text))
        assertEquals(501, state.board!!.count(Section.DONE))
    }
}
