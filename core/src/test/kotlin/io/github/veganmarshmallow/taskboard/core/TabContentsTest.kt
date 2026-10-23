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

/** What each of the five tabs holds (there is no In review tab): placement, Needs you being exclusive, the Done list, and counts. */
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
        assertEquals(listOf(5), board.numbers(Section.NEEDS_YOU)) // in review: waiting for the owner
        assertEquals(listOf(6), board.numbers(Section.DONE))
    }

    @Test fun needsYouIsExclusive() {
        val board = build(issue(2, BLOCKED, NEEDS_OWNER), issue(3, IN_REVIEW), issue(4, IN_REVIEW, NEEDS_OWNER), issue(5, BLOCKED))
        assertEquals(listOf(2, 3, 4), board.numbers(Section.NEEDS_YOU))
        assertEquals(listOf(5), board.numbers(Section.BLOCKED)) // 2 is a question: not here
        assertEquals(1, board.count(Section.BLOCKED))
        val reasons = board.section(Section.NEEDS_YOU).items.associate { it.number to it.needsYouReason }
        assertEquals(mapOf(2 to NeedsYouReason.QUESTION, 3 to NeedsYouReason.REVIEW, 4 to NeedsYouReason.QUESTION), reasons)
    }

    @Test fun everyOpenIssueIsListedAndCountedInExactlyOneTab() {
        // Every status label, alone or mixed, with and without needs-owner and a priority. In-review
        // issues have no tab of their own, so they must all land in Needs you, never nowhere.
        val statuses = listOf(null, TODO, IN_PROGRESS, BLOCKED, IN_REVIEW)
        val issues = (2..401).map { n ->
            val labels = listOfNotNull(statuses[n % 5], NEEDS_OWNER.takeIf { n % 3 == 0 }, PRIORITY_HIGH.takeIf { n % 7 == 0 })
            issue(n, *labels.toTypedArray())
        }
        val board = BoardBuilder(maxPerSection = 1000).build(issues, NOW)
        val open = Section.entries.filter { it != Section.DONE }
        val listed = open.flatMap { board.numbers(it) }
        assertEquals(listed.size, listed.toSet().size) // never twice
        assertEquals((2..401).toSet(), listed.toSet()) // never missing
        assertEquals(400, open.sumOf { board.count(it) }) // counts add up to the open tasks
        open.forEach { assertEquals("$it", board.count(it), board.numbers(it).size) }
        val inReview = issues.filter { IN_REVIEW in it.labels }.map { it.number }
        assertTrue(inReview.isNotEmpty())
        assertTrue(board.numbers(Section.NEEDS_YOU).containsAll(inReview))
        // Each open issue's one tab is exactly what Placement says.
        issues.forEach { assertEquals("#${it.number}", 1, Placement.sectionsOf(it).size) }
        issues.forEach { i -> assertEquals("#${i.number}", Placement.sectionsOf(i), open.filter { i.number in board.numbers(it) }.toSet()) }
        // Only items in Needs you carry a Needs-you badge.
        open.filter { it != Section.NEEDS_YOU }.forEach { tab ->
            assertTrue("$tab", board.section(tab).items.all { it.needsYouReason == null })
        }
    }

    @Test fun placementRules() {
        assertEquals(setOf(Section.TODO), Placement.sectionsOf(issue(2)))
        assertEquals(setOf(Section.NEEDS_YOU), Placement.sectionsOf(issue(2, NEEDS_OWNER)))
        assertEquals(setOf(Section.NEEDS_YOU), Placement.sectionsOf(issue(2, IN_REVIEW)))
        assertEquals(setOf(Section.NEEDS_YOU), Placement.sectionsOf(issue(2, BLOCKED, NEEDS_OWNER)))
        assertEquals(setOf(Section.BLOCKED), Placement.sectionsOf(issue(2, BLOCKED)))
        // In review, with or without other status labels: always Needs you (there is no In review tab).
        assertEquals(setOf(Section.NEEDS_YOU), Placement.sectionsOf(issue(2, IN_REVIEW, DONE)))
        assertEquals(setOf(Section.NEEDS_YOU), Placement.sectionsOf(issue(2, TODO, IN_PROGRESS, IN_REVIEW)))
        assertEquals(NeedsYouReason.REVIEW, Placement.needsYouReason(issue(2, IN_REVIEW, BLOCKED)))
        assertFalse(Placement.canApprove(issue(2, IN_REVIEW)))
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

    @Test fun doneListAtTheSevenDayBoundariesAndWithClockSkew() {
        val week = Duration.ofDays(7)
        val board = build(
            issue(20, DONE, open = false, closedAt = NOW.minus(week)), // exactly 7 days: in, listed last
            issue(21, DONE, open = false, closedAt = NOW.minus(week).minusMillis(1)), // 1 ms too old: out
            issue(22, DONE, open = false, closedAt = NOW.minus(week).plusMillis(1)), // just inside
            issue(23, DONE, open = false, closedAt = NOW.plusSeconds(90)), // closed "in the future": newest
            issue(24, DONE, open = false, closedAt = null), // no close time: out
            issue(25, BLOCKED, open = false, closedAt = NOW), // closed but not done: out
            issue(26, DONE, NEEDS_OWNER, open = false, closedAt = NOW), // a closed question is just done
            issue(1, DONE, open = false, closedAt = NOW), // the summary issue: never
            issue(27, DONE, open = false, closedAt = NOW, pr = true), // a PR: never
        )
        assertEquals(listOf(23, 26, 22, 20), board.numbers(Section.DONE))
        assertEquals(4, board.count(Section.DONE))
        assertEquals(emptyList<Int>(), board.numbers(Section.NEEDS_YOU)) // closed questions leave Needs you
    }

    @Test fun doneTiesAtTheSameInstantAreNewestNumberFirstWhateverTheInputOrder() {
        val input = listOf(5, 9, 2, 7).map { issue(it, DONE, open = false, closedAt = NOW) }
        assertEquals(listOf(9, 7, 5, 2), build(*input.toTypedArray()).numbers(Section.DONE))
        assertEquals(listOf(9, 7, 5, 2), build(*input.reversed().toTypedArray()).numbers(Section.DONE))
    }

    @Test fun corruptCachesFallBackToNothingInsteadOfAWrongDoneTab() {
        val good = WidgetStateCodec.encode(WidgetState(build(issue(2, TODO), done(3, 1)), null, NOW))
        listOf(
            good.replace("\"DONE\"", "\"FINISHED\""), // a tab from another version
            good.replace("\"v\":3", "\"v\":\"3\""), // version of the wrong type
            good.replace("\"v\":3", "\"v\":2"), // relabelled as the six-tab format
            good.replace("\"v\":3", "\"v\":1"), // relabelled as the old format
            good.replace("\"DONE\"", "\"IN_REVIEW\""), // the removed tab
            good.replace("\"total\":1", "\"total\":\"one\""), // count of the wrong type
            good.replace("\"items\":[", "\"items\":[7,"), // an item that is not an object
        ).forEach { assertEquals(it, WidgetState.INITIAL, WidgetStateCodec.decode(it)) }
    }

    @Test fun aCachedTotalBelowItsItemsNeverShowsANegativeMoreRow() {
        val good = WidgetStateCodec.encode(WidgetState(build(done(3, 1), done(4, 2)), null, NOW))
        val skewed = good.replace("\"total\":2", "\"total\":0")
        assertFalse(skewed == good)
        val board = WidgetStateCodec.decode(skewed).board!!
        assertEquals(listOf("I:3", "I:4"), BoardRows.of(board, Section.DONE).map { (it as BoardRow.Item).let { r -> "I:${r.item.number}" } })
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

    @Test fun cacheRoundTripsDoneItemsAndFiveTabs() {
        val board = build(issue(2, IN_REVIEW, NEEDS_OWNER), issue(3, BLOCKED), done(4, 5), done(5, 6))
        val state = WidgetState(board, null, NOW)
        val decoded = WidgetStateCodec.decode(WidgetStateCodec.encode(state))
        assertEquals(state, decoded)
        assertEquals(listOf(4, 5), decoded.board!!.numbers(Section.DONE))
    }

    @Test fun cacheFromTheSixTabVersionWithAnInReviewTabIsDiscarded() {
        // v2 stored an IN_REVIEW section. Starting fresh is safe: the next refresh rebuilds the board.
        val v2 = """{"v":2,"board":{"fetchedAt":0,"sections":[""" +
            listOf("NEEDS_YOU", "TODO", "IN_PROGRESS", "BLOCKED", "IN_REVIEW", "DONE")
                .joinToString(",") { """{"section":"$it","total":0,"items":[]}""" } +
            """]},"error":null,"attemptedAt":null}"""
        assertEquals(WidgetState.INITIAL, WidgetStateCodec.decode(v2))
        // A current-version cache that still names IN_REVIEW is corrupt: also discarded, never a crash.
        assertEquals(WidgetState.INITIAL, WidgetStateCodec.decode(v2.replace("\"v\":2", "\"v\":3")))
    }

    @Test fun cacheFromTheOldFourSectionVersionIsDiscarded() {
        // v1 had no Done list; reading it would show an empty Done tab with a non-zero count, so start fresh.
        val v1 = """{"v":1,"board":{"fetchedAt":0,"done":3,"sections":[{"section":"TODO","total":0,"items":[]}]},"error":null,"attemptedAt":null}"""
        assertEquals(WidgetState.INITIAL, WidgetStateCodec.decode(v1))
    }

    @Test fun hugeBoardKeepsTheCacheSmallWithFiveTabs() {
        val open = (2..2001).map { n ->
            val status = listOf(TODO, IN_PROGRESS, BLOCKED, IN_REVIEW)[n % 4]
            val labels = if (n % 7 == 0) arrayOf(status, NEEDS_OWNER) else arrayOf(status)
            issue(n, *labels, title = "משימה $n " + "x".repeat(150))
        }
        val closed = (3000..3500).map { done(it, 1, DONE) }
        val state = WidgetState(BoardBuilder().build(open + closed, NOW), null, NOW)
        val text = WidgetStateCodec.encode(state)
        assertTrue("five capped tabs: ${text.length}", text.length < 80_000)
        assertEquals(state, WidgetStateCodec.decode(text))
        assertEquals(501, state.board!!.count(Section.DONE))
    }
}
