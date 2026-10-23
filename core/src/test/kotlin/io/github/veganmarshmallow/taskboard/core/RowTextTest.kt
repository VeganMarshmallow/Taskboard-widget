package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.BoardItem
import io.github.veganmarshmallow.taskboard.core.board.BoardLinks
import io.github.veganmarshmallow.taskboard.core.board.BoardRow
import io.github.veganmarshmallow.taskboard.core.board.BoardRows
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.board.TitleCleaner
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.model.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** #12: rows show only the title; the issue number stays on the model for taps and links. */
class RowTextTest {
    @Test fun rowTextIsJustTheTitle() {
        assertEquals("Fix login", BoardItem(7, "Fix login", Priority.NORMAL, canApprove = false).displayText)
    }

    @Test fun numberStaysOnTheItemAndDrivesTheLinkAndRowId() {
        val row = BoardRow.Item(BoardItem(123, "Anything", Priority.HIGH, canApprove = true))
        assertEquals(123, row.item.number)
        assertEquals(123L, row.id)
        assertEquals("https://github.com/VeganMarshmallow/claude-workspace/issues/123", BoardLinks().forRow(row))
    }

    @Test fun noRowOfAnyTabStartsWithItsNumber() {
        val board = BoardBuilder().build(
            listOf(
                issue(2, Labels.TODO), issue(3, Labels.IN_PROGRESS), issue(4, Labels.BLOCKED, Labels.NEEDS_OWNER),
                issue(5, Labels.IN_REVIEW), issue(6, Labels.DONE, open = false, closedAt = NOW),
            ),
            NOW,
        )
        Section.entries.forEach { tab ->
            BoardRows.of(board, tab).filterIsInstance<BoardRow.Item>().forEach {
                assertEquals("Task ${it.item.number}", it.item.displayText)
                assertFalse("$tab", it.item.displayText.startsWith("#"))
            }
        }
    }

    @Test fun titlesThatThemselvesStartWithAHashAreKeptAsWritten() {
        // Only the widget's own prefix is gone; a title's own "#12" is the user's text.
        val board = BoardBuilder().build(listOf(issue(9, Labels.TODO, title = "#12 follow-up: tests")), NOW)
        assertEquals("#12 follow-up: tests", board.section(Section.TODO).items.single().displayText)
    }

    @Test fun emptyHebrewAndLongTitles() {
        fun shown(title: String) = BoardBuilder().build(listOf(issue(2, Labels.TODO, title = title)), NOW)
            .section(Section.TODO).items.single().displayText
        assertEquals(TitleCleaner.UNTITLED, shown("   "))
        assertEquals(TitleCleaner.UNTITLED, shown(""))
        assertEquals("תיקון באג בכפתור", shown("תיקון באג בכפתור"))
        val long = shown("x".repeat(5_000))
        assertEquals(TitleCleaner.MAX_LENGTH, long.length)
        assertFalse(long.startsWith("#"))
    }

    @Test fun hashOnlyAndNumberLikeTitlesAreNeverStrippedOrMistakenForThePrefix() {
        fun shown(n: Int, title: String) = BoardBuilder().build(listOf(issue(n, Labels.TODO, title = title)), NOW)
            .section(Section.TODO).items.single().displayText
        assertEquals("#", shown(2, "#"))
        assertEquals("#2", shown(2, "#2")) // the title is literally its own number: kept
        assertEquals("#5 fix", shown(5, "   #5   fix ")) // cleaned, but the user's # stays
        assertEquals("# heading", shown(3, "# heading"))
        assertEquals("##", shown(4, "##"))
    }

    @Test fun whitespaceOnlyTitlesOfEveryKindBecomeUntitled() {
        listOf("\t", "\n\n", "\u00A0", " \u2028 ", "\u202E\u202C", "\u0000").forEach { raw ->
            val item = BoardBuilder().build(listOf(issue(2, Labels.TODO, title = raw)), NOW).section(Section.TODO).items.single()
            assertEquals(raw.map { it.code }.toString(), TitleCleaner.UNTITLED, item.displayText)
        }
    }

    @Test fun hebrewTitlesStartingWithAHashOrDigitsAreKeptAsWritten() {
        fun shown(title: String) = BoardBuilder().build(listOf(issue(8, Labels.IN_PROGRESS, title = title)), NOW)
            .section(Section.IN_PROGRESS).items.single().displayText
        assertEquals("#3 תיקון", shown("#3 תיקון"))
        assertEquals("3 משימות לשבוע", shown("3 משימות לשבוע"))
        assertEquals("תיקון #8", shown("תיקון #8")) // its own number at the end: the user's text
        assertEquals("סיום", shown("\u202Bסיום\u202C")) // embedding controls dropped, Hebrew kept
    }

    @Test fun doneRowsHaveNoNumberEither() {
        val board = BoardBuilder().build(listOf(issue(44, Labels.DONE, title = "Ship it", open = false, closedAt = NOW)), NOW)
        assertEquals("Ship it", board.section(Section.DONE).items.single().displayText)
    }
}
