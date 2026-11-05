package io.github.veganmarshmallow.taskboard

import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.TabTestViews.assertTabsFit
import io.github.veganmarshmallow.taskboard.TabTestViews.configured
import io.github.veganmarshmallow.taskboard.TabTestViews.shownTab
import io.github.veganmarshmallow.taskboard.TabTestViews.topIn
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.preview.NoteFold
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.theme.ThemeChoice
import io.github.veganmarshmallow.taskboard.data.ThemeStore
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.preview.ActionRow
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
            // #46 round 2: a clean screen. The token steps are folded; every button label is one line.
            assertTrue("token steps folded", !root.visible(R.id.token_help))
            assertTrue("setup steps link", root.visible(R.id.token_help_toggle))
            listOf(R.id.test, R.id.save, R.id.create_token, R.id.token_help_toggle, R.id.update_check).forEach {
                assertEquals("${root.resources.getResourceEntryName(it)}: one line", 1, root.findViewById<Button>(it).lineCount)
            }
        }
        // A second shot scrolled to the token field, the buttons, the Appearance section and the
        // update card, so the owner sees the switch itself (#46 review).
        ScreenshotCapture.activity("46-1-$name-settings-appearance", activity) { root ->
            val scroll = root.findViewById<ScrollView>(R.id.setup_scroll)
            val token = root.findViewById<View>(R.id.token)
            val at = Rect().also { token.getDrawingRect(it) }
            scroll.offsetDescendantRectToMyCoords(token, at)
            scroll.scrollTo(0, at.top - (48 * root.resources.displayMetrics.density).toInt())
            assertTrue("theme switch on screen", root.findViewById<View>(R.id.theme_choice).getGlobalVisibleRect(Rect()))
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

    /**
     * #46 round 2, the owner's tab point: unselected tabs are pills too, a shade off the widget's
     * background (not bare text, not outlines), and the selected one is clearly another colour.
     */
    @Test fun unselectedTabsArePills() = forEachTheme { _, name ->
        listOf(false to "46-9-$name-tab-pills", true to "46-10-$name-tab-pills-dark").forEach { (night, shot) ->
            val host = configured(context, night = night)
            val widget = ScreenshotCapture.widget(shot, realistic, heightDp = 160, tab = Section.TODO, host = host)
            val selected = fill(widget.shownTab(Section.TODO))
            Section.entries.filter { it != Section.TODO }.forEach { section ->
                val pill = fill(widget.shownTab(section))
                assertTrue("$shot $section: has a fill", Color.alpha(pill) > 0)
                assertTrue("$shot $section: differs from the selected tab", pill != selected)
            }
        }
    }

    /** The solid fill of a tab's pill. */
    private fun fill(tab: TextView): Int {
        val shape = tab.background as? GradientDrawable
        assertNotNull("${tab.text}: a pill background", shape)
        val color = shape!!.color
        assertNotNull("${tab.text}: a solid fill", color)
        return color!!.defaultColor
    }

    /**
     * #46 round 2, the owner's sheet point: Send, Merge and Open on GitHub on one line, and "Show more"
     * at the end of the note's own line ("Ready to merge…  Show more"). Light and dark.
     */
    @Test fun sheetActionsOnOneLine() = forEachTheme { _, name ->
        listOf(false to "46-11-$name-sheet-actions", true to "46-12-$name-sheet-actions-dark").forEach { (night, shot) ->
            val sheet = ScreenshotCapture.preview(
                shot, FakePreviews.inReviewReadyToMerge(), widthDp = 380, heightDp = 560,
                host = { PreviewHost(context, night = night) },
            )
            val row = sheet.findViewById<View>(R.id.preview_actions)
            val actions = listOf(R.id.preview_open, R.id.preview_reply_send, R.id.preview_merge).map { sheet.findViewById<Button>(it) }
            actions.forEach { button ->
                val what = "$shot ${button.text}"
                assertTrue("$what shown", button.visibility == View.VISIBLE)
                assertTrue("$what in the action row", button.parent === row)
                assertEquals("$what: one line", 1, button.lineCount)
                assertEquals("$what: label not cut short", 0, button.layout.getEllipsisCount(0))
                assertTrue("$what: inside the row", button.right <= row.width)
                assertTrue("$what: same line as Open", button.top < actions[0].bottom && actions[0].top < button.bottom)
            }
            assertTrue("$shot: one line, not stacked", !(row as ActionRow).isStacked)

            val note = sheet.findViewById<TextView>(R.id.preview_claude_text)
            val toggle = sheet.findViewById<TextView>(R.id.preview_claude_toggle)
            assertEquals(context.getString(R.string.preview_show_more), toggle.text.toString())
            assertEquals(NoteFold.COLLAPSED_LINES, note.maxLines)
            val noteTop = note.topIn(sheet)
            val toggleTop = toggle.topIn(sheet)
            assertTrue("$shot: Show more on the note's line", toggleTop < noteTop + note.height && noteTop < toggleTop + toggle.height)
            assertTrue("$shot: Show more after the note", toggle.left >= note.right)
        }
    }

    /**
     * #46 round 2 edge cases: a 320dp phone. At the largest font Send, Merge and Open can't share a
     * line, so the row stacks (one full-width button per line) rather than squeezing "Open on GitHub"
     * to nothing or clipping Merge. Either way every label is whole. And a one-line note gets no toggle.
     */
    @Test fun sheetActionsOnANarrowPhone() = forEachTheme { _, name ->
        listOf(1.0f, 2.0f).forEach { scale ->
            val shot = "46-13-$name-sheet-actions-320dp-font-$scale"
            val sheet = ScreenshotCapture.preview(
                shot, FakePreviews.inReviewReadyToMerge(), widthDp = 320, heightDp = 640,
                host = { PreviewHost(context, fontScale = scale) },
            )
            val row = sheet.findViewById<ActionRow>(R.id.preview_actions)
            // Normal size: one line or stacked, whichever the theme's fonts allow (the shot shows which).
            if (scale == 2.0f) assertTrue("$shot: stacked", row.isStacked)
            listOf(R.id.preview_open, R.id.preview_reply_send, R.id.preview_merge).map { sheet.findViewById<Button>(it) }.forEach { button ->
                val what = "$shot ${button.text}"
                assertTrue("$what shown", button.visibility == View.VISIBLE)
                assertEquals("$what: label not cut short", 0, button.layout.getEllipsisCount(0))
                assertTrue("$what: inside the row", button.left >= 0 && button.right <= row.width && button.bottom <= row.height)
            }
        }
        val short = ScreenshotCapture.preview(
            "46-14-$name-note-one-line", FakePreviews.inReviewWithNote("✅ Ready to merge."), widthDp = 320, heightDp = 640,
        )
        assertTrue("one-line note: no Show more", !short.visible(R.id.preview_claude_toggle))
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
