package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.board.TabSelection
import io.github.veganmarshmallow.taskboard.core.model.Labels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabSelectionTest {
    private fun board(vararg issues: io.github.veganmarshmallow.taskboard.core.model.Issue) = BoardBuilder().build(issues.toList(), NOW)

    @Test fun defaultIsNeedsYouWhenItHasItems() {
        assertEquals(Section.NEEDS_YOU, TabSelection.default(board(issue(2, Labels.NEEDS_OWNER))))
        assertEquals(Section.NEEDS_YOU, TabSelection.default(board(issue(2, Labels.IN_REVIEW), issue(3, Labels.TODO))))
    }

    @Test fun defaultIsTodoOtherwise() {
        assertEquals(Section.TODO, TabSelection.default(board(issue(2, Labels.IN_PROGRESS), issue(3, Labels.BLOCKED))))
        assertEquals(Section.TODO, TabSelection.default(board())) // empty board
        assertEquals(Section.TODO, TabSelection.default(null)) // nothing loaded yet
        // Only done work, or only the summary issue / PRs needing review: still Todo.
        assertEquals(Section.TODO, TabSelection.default(board(issue(4, Labels.DONE, open = false, closedAt = NOW))))
        assertEquals(Section.TODO, TabSelection.default(board(issue(1, Labels.NEEDS_OWNER), issue(9, Labels.IN_REVIEW, pr = true))))
    }

    @Test fun defaultFollowsTheBoardUntilTheUserPicks() {
        val quiet = board(issue(2, Labels.TODO))
        val busy = board(issue(2, Labels.TODO), issue(3, Labels.NEEDS_OWNER))
        assertEquals(Section.TODO, TabSelection.resolve(null, quiet))
        assertEquals(Section.NEEDS_YOU, TabSelection.resolve(null, busy))
        assertEquals(Section.TODO, TabSelection.resolve(null, quiet)) // the question was answered
    }

    @Test fun aSavedPickAlwaysWins() {
        val busy = board(issue(3, Labels.NEEDS_OWNER))
        Section.entries.forEach { tab ->
            assertEquals(tab, TabSelection.resolve(TabSelection.key(tab), busy))
            assertEquals(tab, TabSelection.resolve(TabSelection.key(tab), null))
            assertEquals(tab, TabSelection.resolve(TabSelection.key(tab), board()))
        }
    }

    @Test fun pickingAnEmptyTabIsRemembered() {
        // Even an empty Needs you stays selected once chosen; the user sees its empty message.
        assertEquals(Section.NEEDS_YOU, TabSelection.resolve(TabSelection.key(Section.NEEDS_YOU), board(issue(2, Labels.TODO))))
    }

    @Test fun keysRoundTripAndAreStableNames() {
        Section.entries.forEach { assertEquals(it, TabSelection.parse(TabSelection.key(it))) }
        assertEquals(listOf("NEEDS_YOU", "TODO", "IN_PROGRESS", "BLOCKED", "DONE"), Section.entries.map(TabSelection::key))
        assertEquals(Section.DONE, TabSelection.parse("  DONE \n")) // padding tolerated
    }

    @Test fun aSavedPickOfTheRemovedReviewTabFallsBackToTheDefaultRule() {
        // Widgets set up before the Review tab was removed may have "IN_REVIEW" stored.
        assertNull(TabSelection.parse("IN_REVIEW"))
        assertNull(TabSelection.parse(" IN_REVIEW\n"))
        val quiet = board(issue(2, Labels.TODO))
        val waiting = board(issue(2, Labels.TODO), issue(3, Labels.IN_REVIEW))
        assertEquals(Section.TODO, TabSelection.resolve("IN_REVIEW", quiet))
        assertEquals(Section.NEEDS_YOU, TabSelection.resolve("IN_REVIEW", waiting)) // the PR waiting is in Needs you
        assertEquals(Section.TODO, TabSelection.resolve("IN_REVIEW", null))
        assertEquals(TabSelection.default(waiting), TabSelection.resolve("IN_REVIEW", waiting))
    }

    @Test fun malformedOrUnknownKeysFallBackToTheDefault() {
        val busy = board(issue(3, Labels.NEEDS_OWNER))
        listOf(null, "", "   ", "done", "Todo", "ARCHIVED", "IN_REVIEW", "in_review", "3", "NEEDS_YOU;DROP", "תור", "TODO\u0000", "x".repeat(100_000)).forEach {
            assertNull(it?.take(20), TabSelection.parse(it))
            assertEquals(it?.take(20), Section.NEEDS_YOU, TabSelection.resolve(it, busy))
            assertEquals(it?.take(20), Section.TODO, TabSelection.resolve(it, null))
        }
    }
}
