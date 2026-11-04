package io.github.veganmarshmallow.taskboard

import android.content.Intent
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.TabTestViews.assertTabsFit
import io.github.veganmarshmallow.taskboard.TabTestViews.configured
import io.github.veganmarshmallow.taskboard.TabTestViews.shownTab
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.preview.NoteFold
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.theme.ThemeChoice
import io.github.veganmarshmallow.taskboard.data.ThemeStore
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.setup.SetupActivity
import io.github.veganmarshmallow.taskboard.setup.ThemeSection
import io.github.veganmarshmallow.taskboard.theme.WidgetPalette
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #46: the two looks, calm (A, the default) and ink and paper (B), side by side for the owner to judge:
 * the settings screen with the theme switch, the widget (light and dark, Merge and Approve rows), the
 * tab labels' balance between name and count, and Claude's note folded to one line and expanded.
 * Names are `46-<n>-<theme>-<what>`.
 */
@Screenshot
@RunWith(AndroidJUnit4::class)
class ThemeScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val themes = ThemeStore(context)
    private val realistic = WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW)

    @After fun restoreDefault() {
        themes.save(ThemeChoice.DEFAULT)
    }

    private fun forEachTheme(block: (ThemeChoice, String) -> Unit) = ThemeChoice.entries.forEach {
        themes.save(it)
        block(it, it.key)
    }

    private fun View.visible(id: Int) = findViewById<View>(id).visibility == View.VISIBLE
    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun rows(widget: View): List<View> {
        val list = widget.findViewById<LinearLayout>(R.id.board_list)
        return (0 until list.childCount).map { list.getChildAt(it) }
    }

    @Test fun settingsScreenWithTheThemeSwitch() = forEachTheme { choice, name ->
        TokenStore(context).clear() // no token: the screen doesn't check for updates on open
        val intent = Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        ScreenshotCapture.activity("46-1-$name-settings", activity) { root ->
            assertTrue(root.findViewById<RadioButton>(ThemeSection.buttonOf(choice)).isChecked)
            ThemeChoice.entries.filter { it != choice }.forEach {
                assertTrue("$it not checked", !root.findViewById<RadioButton>(ThemeSection.buttonOf(it)).isChecked)
            }
        }
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test fun widgetWithMergeRows() = forEachTheme { choice, name ->
        val palette = WidgetPalette.of(choice)
        val widget = ScreenshotCapture.widget("46-2-$name-widget-merge", FakeMerges.boardState(), heightDp = 300, tab = Section.NEEDS_YOU)
        assertEquals(palette.serifTitle, widget.visible(R.id.title_serif))
        assertEquals(!palette.serifTitle, widget.visible(R.id.title))
        val merges = rows(widget).map { it.findViewById<TextView>(R.id.item_merge) }.filter { it.visibility == View.VISIBLE }
        assertTrue("has Merge rows", merges.isNotEmpty())
        merges.forEach { assertEquals(context.getColor(palette.mergeText), it.currentTextColor) }
    }

    @Test fun widgetTodoInDarkMode() = forEachTheme { choice, name ->
        val palette = WidgetPalette.of(choice)
        val dark = configured(context, night = true)
        val widget = ScreenshotCapture.widget("46-3-$name-widget-todo-dark", realistic, tab = Section.TODO, host = dark)
        val approves = rows(widget).map { it.findViewById<TextView>(R.id.item_approve) }.filter { it.visibility == View.VISIBLE }
        assertTrue("has Approve rows", approves.isNotEmpty())
        approves.forEach { assertEquals(dark.getColor(palette.approveText), it.currentTextColor) }
        assertEquals(dark.getColor(palette.tabSelectedText), widget.shownTab(Section.TODO).currentTextColor)
    }

    /** The owner's tab point: the name leads (medium weight) and the count sits beside it at the same size. */
    @Test fun tabLabelsBalanceNameAndCount() = forEachTheme { _, name ->
        ScreenshotCapture.widget("46-4-$name-tabs", realistic, heightDp = 160, tab = Section.NEEDS_YOU)
        val narrow = ScreenshotCapture.widget("46-5-$name-tabs-narrow-180dp", realistic, widthDp = 180, heightDp = 200, tab = Section.TODO)
        assertTabsFit(narrow, "$name 180dp")
        Section.entries.forEach { section ->
            val tab = narrow.shownTab(section)
            val text = tab.text
            assertTrue("$section label is styled", text is Spanned)
            val spans = (text as Spanned).getSpans(0, text.length, TypefaceSpan::class.java)
            assertEquals("$section: one medium-weight name", 1, spans.size)
            val nameEnd = text.getSpanEnd(spans[0])
            assertTrue("$section: the count is outside the name", text.substring(nameEnd).trim().isNotEmpty())
        }
        assertEquals("Todo 2", narrow.shownTab(Section.TODO).text.toString())
    }

    @Test fun claudeNoteFoldsToOneLine() = forEachTheme { choice, name ->
        val collapsed = ScreenshotCapture.preview("46-6-$name-note-collapsed", FakePreviews.blockedWithLongComment(), widthDp = 380, heightDp = 640)
        assertEquals(NoteFold.COLLAPSED_LINES, collapsed.findViewById<TextView>(R.id.preview_claude_text).maxLines)
        assertEquals(context.getString(R.string.preview_show_more), collapsed.text(R.id.preview_claude_toggle))
        val chip = collapsed.findViewById<TextView>(R.id.preview_status)
        if (choice == ThemeChoice.CALM) assertNotNull(chip.backgroundTintList) else assertNull(chip.backgroundTintList)

        val expanded = ScreenshotCapture.preview(
            "46-7-$name-note-expanded", FakePreviews.blockedWithLongComment(), widthDp = 380, heightDp = 760,
            prepare = { it.findViewById<View>(R.id.preview_claude_toggle).performClick() },
        )
        assertEquals(Int.MAX_VALUE, expanded.findViewById<TextView>(R.id.preview_claude_text).maxLines)
        assertEquals(context.getString(R.string.preview_show_less), expanded.text(R.id.preview_claude_toggle))
    }

    @Test fun claudeNoteInDarkMode() = forEachTheme { _, name ->
        val v = ScreenshotCapture.preview(
            "46-8-$name-note-dark", FakePreviews.hebrewBlockedComment(), widthDp = 380, heightDp = 640,
            host = { PreviewHost(context, night = true) },
        )
        assertEquals(NoteFold.COLLAPSED_LINES, v.findViewById<TextView>(R.id.preview_claude_text).maxLines)
    }
}
