package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.preview.NoteFold
import io.github.veganmarshmallow.taskboard.core.preview.NoteFold.Shape
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteFoldTest {
    private val whole = Shape(Int.MAX_VALUE, showToggle = false, toggleShowsLess = false)

    @Test fun collapsesToOneLine() {
        assertEquals(1, NoteFold.COLLAPSED_LINES)
        assertEquals(Shape(1, showToggle = true, toggleShowsLess = false), NoteFold.shape(lineCount = 5, expanded = false))
    }

    @Test fun expandedShowsEverythingAndOffersLess() {
        assertEquals(Shape(Int.MAX_VALUE, showToggle = true, toggleShowsLess = true), NoteFold.shape(lineCount = 5, expanded = true))
    }

    @Test fun aNoteThatFitsHasNoToggle() {
        assertEquals(whole, NoteFold.shape(lineCount = 1, expanded = false))
        assertEquals(whole, NoteFold.shape(lineCount = 1, expanded = true))
    }

    @Test fun boundaryJustOverTheLimit() {
        assertEquals(Shape(1, true, false), NoteFold.shape(lineCount = 2, expanded = false))
        assertEquals(whole, NoteFold.shape(lineCount = 4, expanded = false, collapsedLines = 4))
        assertEquals(Shape(4, true, false), NoteFold.shape(lineCount = 5, expanded = false, collapsedLines = 4))
    }

    @Test fun unmeasuredOrEmptyNotesShowWhole() {
        assertEquals(whole, NoteFold.shape(lineCount = 0, expanded = false))
        assertEquals(whole, NoteFold.shape(lineCount = -3, expanded = true))
    }

    @Test fun aLimitBelowOneCountsAsOne() {
        assertEquals(Shape(1, true, false), NoteFold.shape(lineCount = 3, expanded = false, collapsedLines = 0))
        assertEquals(Shape(1, true, false), NoteFold.shape(lineCount = 3, expanded = false, collapsedLines = -5))
        assertEquals(whole, NoteFold.shape(lineCount = 1, expanded = false, collapsedLines = 0))
    }

    @Test fun hugeNotes() {
        assertEquals(Shape(1, true, false), NoteFold.shape(lineCount = Int.MAX_VALUE, expanded = false))
        assertEquals(Shape(Int.MAX_VALUE, true, true), NoteFold.shape(lineCount = Int.MAX_VALUE, expanded = true))
    }
}
