package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.BoardLinks
import io.github.veganmarshmallow.taskboard.core.board.BoardRow
import io.github.veganmarshmallow.taskboard.core.board.BoardRows
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.filter.BoardFilter
import io.github.veganmarshmallow.taskboard.core.filter.FilterOptions
import io.github.veganmarshmallow.taskboard.core.filter.filtered
import io.github.veganmarshmallow.taskboard.core.filter.openCount
import io.github.veganmarshmallow.taskboard.core.github.IssueJsonParser
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.model.Labels.BLOCKED
import io.github.veganmarshmallow.taskboard.core.model.Labels.DONE
import io.github.veganmarshmallow.taskboard.core.model.Labels.IN_PROGRESS
import io.github.veganmarshmallow.taskboard.core.model.Labels.IN_REVIEW
import io.github.veganmarshmallow.taskboard.core.model.Labels.NEEDS_OWNER
import io.github.veganmarshmallow.taskboard.core.model.Labels.PRIORITY_HIGH
import io.github.veganmarshmallow.taskboard.core.model.Labels.PRIORITY_LOW
import io.github.veganmarshmallow.taskboard.core.model.Labels.TODO
import io.github.veganmarshmallow.taskboard.core.model.Priority
import io.github.veganmarshmallow.taskboard.core.model.Repo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** #59: the board filter's matching, how it changes the board, and the values it offers. */
class BoardFilterTest {
    private val widget = Repo("VeganMarshmallow", "Taskboard-widget")
    private val site = Repo("VeganMarshmallow", "site")

    private fun task(n: Int, vararg labels: String, title: String = "Task $n", repo: Repo? = null, open: Boolean = true) =
        Issue(n, title, labels.map(Labels::normalize).toSet(), open, if (open) null else NOW.minusSeconds(3_600), repo = repo)

    private fun board(vararg issues: Issue): Board = BoardBuilder().build(issues.toList(), NOW)

    private val sample = board(
        task(2, TODO, Labels.type("bug"), title = "Fix login redirect", repo = widget),
        task(3, TODO, Labels.type("feature"), PRIORITY_HIGH, title = "Café menu in Hebrew", repo = site),
        task(4, IN_PROGRESS, Labels.type("feature"), title = "Add a filter", repo = widget),
        task(5, BLOCKED, NEEDS_OWNER, Labels.type("research"), PRIORITY_LOW, title = "Which chart library?"),
        task(6, IN_REVIEW, Labels.type("bug"), title = "Crash on rotate", repo = widget),
        task(7, DONE, Labels.type("bug"), title = "Old bug", repo = widget, open = false),
    )

    private fun Board.numbers() = sections.flatMap { s -> s.items.map { it.number } }.sorted()
    private fun shown(filter: BoardFilter) = sample.filtered(filter).numbers()

    @Test fun anInactiveFilterLeavesTheBoardAlone() {
        assertFalse(BoardFilter.NONE.isActive)
        assertSame(sample, sample.filtered(BoardFilter.NONE))
        assertSame(sample, sample.filtered(BoardFilter(text = "   \t ")))
        assertEquals(0, BoardFilter(text = "  ").activeCount)
    }

    @Test fun anEmptyBoardStaysEmpty() {
        val empty = board()
        val filtered = empty.filtered(BoardFilter(repos = setOf("a/b"), text = "x"))
        assertTrue(filtered.isEmpty)
        assertEquals(Section.entries, filtered.sections.map { it.section })
        Section.entries.forEach { assertEquals(listOf(BoardRow.Empty(it, filtered = true)), BoardRows.of(filtered, it, filtered = true)) }
        assertTrue(FilterOptions.of(empty).isEmpty)
        assertTrue(FilterOptions.of(null).isEmpty)
    }

    @Test fun noMatchesEmptiesEveryTabAndZeroesTheCounts() {
        val filtered = sample.filtered(BoardFilter(text = "zzz-nothing"))
        Section.entries.forEach { assertEquals(0, filtered.count(it)) }
        assertEquals(0, filtered.openCount)
        assertEquals(5, sample.openCount)
    }

    @Test fun eachDimensionFiltersAndTabCountsFollow() {
        assertEquals(listOf(2, 4, 6, 7), shown(BoardFilter(repos = setOf(widget.fullName))))
        assertEquals(listOf(2, 6, 7), shown(BoardFilter(types = setOf("bug"))))
        assertEquals(listOf(3), shown(BoardFilter(priorities = setOf(Priority.HIGH))))
        assertEquals(listOf(2, 4, 6, 7), shown(BoardFilter(priorities = setOf(Priority.NORMAL))))
        val bugs = sample.filtered(BoardFilter(types = setOf("bug")))
        assertEquals(1, bugs.count(Section.NEEDS_YOU)) // Needs you follows the filter too
        assertEquals(1, bugs.count(Section.TODO))
        assertEquals(0, bugs.count(Section.IN_PROGRESS))
        assertEquals(1, bugs.count(Section.DONE))
    }

    @Test fun valuesInOneDimensionAreOrDimensionsAreAnd() {
        assertEquals(listOf(2, 3, 4, 6, 7), shown(BoardFilter(repos = setOf(widget.fullName, site.fullName))))
        assertEquals(listOf(4), shown(BoardFilter(repos = setOf(widget.fullName), types = setOf("feature"))))
        assertEquals(listOf(2), shown(BoardFilter(repos = setOf(widget.fullName), types = setOf("bug"), text = "LOGIN")))
        assertEquals(listOf(3, 4), shown(BoardFilter(types = setOf("feature", "nothing"))))
        assertEquals(emptyList<Int>(), shown(BoardFilter(types = setOf("research"), repos = setOf(widget.fullName))))
        assertEquals(4, BoardFilter(repos = setOf("a/b", "c/d"), priorities = setOf(Priority.LOW), text = "x").activeCount)
    }

    @Test fun reposAndTypesIgnoreCaseAndSpaces() {
        assertEquals(listOf(2, 4, 6, 7), shown(BoardFilter(repos = setOf("veganmarshmallow/TASKBOARD-WIDGET"))))
        assertEquals(listOf(2, 6, 7), shown(BoardFilter(types = setOf(" BUG "))))
        val f = BoardFilter(repos = setOf("A/b"))
        assertTrue(f.hasRepo("a/B"))
        assertEquals(emptySet<String>(), f.toggleRepo(" a/b ").repos)
        assertEquals(setOf("A/b", "c/d"), f.toggleRepo("c/d").repos)
        assertEquals(f, f.toggleRepo("  "))
        assertTrue(BoardFilter().toggleType("Bug").hasType("bug"))
        assertEquals(setOf(Priority.LOW), BoardFilter().togglePriority(Priority.LOW).priorities)
        assertEquals(emptySet<Priority>(), BoardFilter(priorities = setOf(Priority.LOW)).togglePriority(Priority.LOW).priorities)
    }

    @Test fun textIgnoresCaseAndDiacriticsAndMatchesNumbersAndRepos() {
        assertEquals(listOf(3), shown(BoardFilter(text = "cafe")))
        assertEquals(listOf(3), shown(BoardFilter(text = "CAFÉ hebrew")))
        assertEquals(listOf(3), shown(BoardFilter(text = "Café"))) // combining accent typed separately
        assertEquals(listOf(4), shown(BoardFilter(text = "#4")))
        assertEquals(listOf(4), shown(BoardFilter(text = "4")))
        assertEquals(listOf(3), shown(BoardFilter(text = "site")))
        assertEquals(emptyList<Int>(), shown(BoardFilter(text = "cafe login"))) // every word must match
        val hebrew = board(task(9, TODO, title = "תַּפְרִיט בעברית"))
        assertEquals(listOf(9), hebrew.filtered(BoardFilter(text = "תפריט")).numbers())
    }

    @Test fun hugeTextIsCutNotFatal() {
        assertEquals(emptyList<Int>(), shown(BoardFilter(text = "x".repeat(100_000))))
        assertEquals(listOf(2), shown(BoardFilter(text = "login" + " ".repeat(300) + "zzz")))
    }

    @Test fun unknownRepoValuesMatchNothing() {
        assertEquals(emptyList<Int>(), shown(BoardFilter(repos = setOf("not a repo"))))
        assertEquals(emptyList<Int>(), shown(BoardFilter(repos = setOf("VeganMarshmallow/gone"))))
        // A task without a Repo field never matches a repo filter, but does match the others.
        assertEquals(listOf(5), shown(BoardFilter(types = setOf("research"))))
    }

    @Test fun aFilteredCappedTabDropsItsMoreRow() {
        val big = BoardBuilder(maxPerSection = 2).build((10..14).map { task(it, TODO, repo = widget) }, NOW)
        assertTrue(BoardRows.of(big, Section.TODO).last() is BoardRow.More)
        val filtered = big.filtered(BoardFilter(repos = setOf(widget.fullName)))
        assertEquals(2, filtered.count(Section.TODO))
        assertTrue(BoardRows.of(filtered, Section.TODO, filtered = true).all { it is BoardRow.Item })
    }

    @Test fun optionsListOnlyWhatIsOnTheBoard() {
        val options = FilterOptions.of(sample)
        assertEquals(listOf(site.fullName, widget.fullName), options.repos)
        assertEquals(listOf("feature", "bug", "research"), options.types)
        assertEquals(listOf(Priority.HIGH, Priority.NORMAL, Priority.LOW), options.priorities)
        assertEquals(listOf("feature", "chore"), FilterOptions.of(board(task(1, TODO, Labels.type("chore")), task(2, TODO, Labels.type("feature")))).types)
    }

    @Test fun aPickedValueNoLongerOnTheBoardIsStillOffered() {
        val stale = BoardFilter(repos = setOf("VeganMarshmallow/gone", "veganmarshmallow/SITE"), types = setOf("docs"))
        val options = FilterOptions.of(sample, stale)
        assertEquals(listOf("VeganMarshmallow/gone", site.fullName, widget.fullName), options.repos)
        assertTrue("docs" in options.types)
        assertEquals(listOf(3), shown(stale.copy(types = emptySet())))
    }

    @Test fun builderKeepsRepoAndTypesOnItems() {
        val item = sample.section(Section.TODO).items.first { it.number == 2 }
        assertEquals(widget.fullName, item.repo)
        assertEquals(setOf("bug"), item.types)
        assertNull(sample.section(Section.BLOCKED).items.single().repo)
        assertEquals(widget.fullName, sample.section(Section.DONE).items.single().repo)
    }

    @Test fun closedIssuesKeepTheirRepoSoDoneCanBeFiltered() {
        val closed = IssueJsonParser.parsePage(
            """[{"number":7,"title":"t","state":"closed","closed_at":"2026-10-22T10:00:00Z","labels":[],"body":"Repo: VeganMarshmallow/site"}]""",
        ).single()
        assertEquals(site, closed.repo)
    }

    @Test fun aFilteredEmptyTodoDoesNotInviteANewTask() {
        val links = BoardLinks()
        assertNull(links.forRow(BoardRow.Empty(Section.TODO, filtered = true)))
        assertEquals(links.newIssue(), links.forRow(BoardRow.Empty(Section.TODO)))
    }
}
