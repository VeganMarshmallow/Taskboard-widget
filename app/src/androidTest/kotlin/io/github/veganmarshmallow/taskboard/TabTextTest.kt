package io.github.veganmarshmallow.taskboard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.board.CountBadge
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.widget.TabText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Tab words come from resources: every tab has both names, and the English text is unchanged. */
@RunWith(AndroidJUnit4::class)
class TabTextTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun everyTabHasANonBlankShortNameAndTitle() {
        Section.entries.forEach {
            assertTrue("$it name", context.getString(TabText.name(it)).isNotBlank())
            assertTrue("$it title", context.getString(TabText.title(it)).isNotBlank())
        }
    }

    @Test fun englishNamesAndTitlesAreUnchanged() {
        assertEquals(listOf("Needs you", "Todo", "Doing", "Blocked", "Done"), Section.entries.map { context.getString(TabText.name(it)) })
        assertEquals(listOf("Needs you", "Todo", "In progress", "Blocked", "Done"), Section.entries.map { context.getString(TabText.title(it)) })
    }

    @Test fun labelsAreShortNameAndCappedCount() {
        assertEquals("Todo 0", TabText.label(context, Section.TODO, 0))
        assertEquals("Doing 2", TabText.label(context, Section.IN_PROGRESS, 2))
        assertEquals("Todo 999", TabText.label(context, Section.TODO, 999))
        assertEquals("Todo 999+", TabText.label(context, Section.TODO, 1000))
        assertEquals("Done 999+", TabText.label(context, Section.DONE, Int.MAX_VALUE))
        assertEquals("Blocked 0", TabText.label(context, Section.BLOCKED, -5))
        assertEquals("Needs you 0", TabText.label(context, Section.NEEDS_YOU, Int.MIN_VALUE))
    }

    @Test fun countTextForEachBadge() {
        assertEquals("7", TabText.countText(context, CountBadge.Exact(7)))
        assertEquals("999+", TabText.countText(context, CountBadge.Capped(999)))
    }

    @Test fun exactAndCappedCountsUseTheSameLocaleDigits() {
        val arabic = TabTestViews.configured(context, locale = java.util.Locale("ar", "EG"))
        val exact = TabText.countText(arabic, CountBadge.Exact(999))
        val capped = TabText.countText(arabic, CountBadge.Capped(999))
        assertEquals(exact + "+", capped)
        assertEquals(String.format(java.util.Locale("ar", "EG"), "%d", 999), exact)
    }
}
