package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.TabLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabLabelTest {
    private fun String.at(range: IntRange?) = range?.let { substring(it) }

    @Test fun countAfterTheName() {
        val label = "Needs you 3"
        assertEquals("3", label.at(TabLabel.countRange(label, "3")))
        assertEquals("Needs you", label.at(TabLabel.nameRange(label, "3")))
    }

    @Test fun countBeforeTheName() {
        val label = "12 משימות"
        assertEquals("12", label.at(TabLabel.countRange(label, "12")))
        assertEquals("משימות", label.at(TabLabel.nameRange(label, "12")))
    }

    @Test fun cappedCount() {
        val label = "Done 999+"
        assertEquals("999+", label.at(TabLabel.countRange(label, "999+")))
        assertEquals("Done", label.at(TabLabel.nameRange(label, "999+")))
    }

    @Test fun aNameWithTheSameDigitsUsesTheLastOccurrence() {
        val label = "Q3 3"
        assertEquals(3..3, TabLabel.countRange(label, "3"))
        assertEquals("Q3", label.at(TabLabel.nameRange(label, "3")))
    }

    /** Review (#46): a count-first label whose name has the same digits keeps the leading count. */
    @Test fun countFirstWithTheSameDigitsInTheName() {
        val label = "3 Q3"
        assertEquals(0..0, TabLabel.countRange(label, "3"))
        assertEquals("Q3", label.at(TabLabel.nameRange(label, "3")))
    }

    @Test fun surroundingSpacesDoNotHideTheCount() {
        val label = " Todo 3 "
        assertEquals(6..6, TabLabel.countRange(label, "3"))
        assertEquals("Todo", label.at(TabLabel.nameRange(label, "3")))
    }

    @Test fun missingOrEmptyCount() {
        assertNull(TabLabel.countRange("Todo", "4"))
        assertNull(TabLabel.countRange("Todo 4", ""))
        assertEquals("Todo", "Todo".at(TabLabel.nameRange("Todo", "4")))
        assertEquals("Todo", " Todo ".at(TabLabel.nameRange(" Todo ", "")))
    }

    @Test fun labelThatIsOnlyTheCount() {
        assertEquals(0..0, TabLabel.countRange("7", "7"))
        assertNull(TabLabel.nameRange("7", "7"))
        assertNull(TabLabel.nameRange("  7  ", "7"))
    }

    @Test fun emptyLabel() {
        assertNull(TabLabel.countRange("", "1"))
        assertNull(TabLabel.nameRange("", "1"))
        assertNull(TabLabel.nameRange("   ", ""))
    }

    @Test fun localeDigits() {
        val label = "Todo ٩٩٩"
        assertEquals("٩٩٩", label.at(TabLabel.countRange(label, "٩٩٩")))
        assertEquals("Todo", label.at(TabLabel.nameRange(label, "٩٩٩")))
    }

    @Test fun countInTheMiddleKeepsTheLongerSide() {
        val label = "In 2 progress"
        assertEquals("progress", label.at(TabLabel.nameRange(label, "2")))
    }
}
