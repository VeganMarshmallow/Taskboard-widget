package io.github.veganmarshmallow.taskboard

import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.preview.PreviewScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Screenshots of the task preview sheet (issue #11). Each also checks what the picture claims. */
@RunWith(AndroidJUnit4::class)
class PreviewScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun capture(
        name: String,
        screen: PreviewScreen,
        heightDp: Int = 420,
        widthDp: Int = 380,
        host: () -> PreviewHost = { PreviewHost(context) },
        prepare: (View) -> Unit = {},
    ): View {
        lateinit var sheet: View
        instrumentation.runOnMainSync {
            val rendered = host().render(screen, widthDp = widthDp, heightDp = heightDp, prepare = prepare)
            sheet = rendered.sheet
            Screenshots.save(context, name, WidgetHost.toBitmap(rendered.root))
        }
        return sheet
    }

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun View.visible(id: Int) = findViewById<View>(id).visibility == View.VISIBLE

    @Test fun todoWithApprove() {
        val v = capture("11-1-preview-todo-approve", FakePreviews.todoLoaded())
        assertTrue(v.visible(R.id.preview_approve))
        assertEquals("Todo", v.text(R.id.preview_status))
        assertEquals("#11", v.text(R.id.preview_number))
        assertTrue(v.text(R.id.preview_summary).startsWith("Tapping a task opens a preview"))
        assertTrue(v.visible(R.id.preview_open))
    }

    @Test fun blockedWithClaudeComment() {
        val v = capture("11-2-preview-blocked-claude-line", FakePreviews.blockedWithClaudeLine())
        assertTrue(v.text(R.id.preview_claude_text).startsWith("⛔ Blocked: Chart.js"))
        assertEquals("Blocked: waiting on you", v.text(R.id.preview_status)) // #34
        assertTrue(!v.visible(R.id.preview_approve))
    }

    @Test fun hebrew() {
        val v = capture("11-3-preview-hebrew", FakePreviews.hebrewInProgress())
        assertEquals("In progress", v.text(R.id.preview_status))
        assertTrue(v.visible(R.id.preview_claude))
    }

    @Test fun cachedRowWhileLoading() {
        val v = capture("11-4-preview-loading-from-cache", FakePreviews.cachedWhileLoading(), heightDp = 300)
        assertTrue(v.visible(R.id.preview_progress))
        assertEquals(context.getString(R.string.loading), v.text(R.id.preview_summary))
        assertTrue(!v.visible(R.id.preview_approve))
    }

    @Test fun loadFailedKeepsCachedRow() {
        val v = capture("11-5-preview-error-stale", FakePreviews.failedWithCache(), heightDp = 340)
        assertTrue(v.visible(R.id.preview_error))
        assertTrue(v.visible(R.id.preview_retry))
        assertTrue(v.text(R.id.preview_error).contains("No network"))
        assertTrue(!v.visible(R.id.preview_summary))
        assertTrue(v.visible(R.id.preview_approve)) // the cached row could approve, nothing says otherwise
    }

    @Test fun justApproved() {
        val v = capture("11-6-preview-approved", FakePreviews.justApproved())
        assertTrue(v.visible(R.id.preview_approved))
        assertTrue(!v.visible(R.id.preview_approve))
    }

    // --- added in review ---

    @Test fun issueDeletedOrMoved() {
        val v = capture("11-7-preview-issue-gone", FakePreviews.issueGone(), heightDp = 340)
        assertTrue(v.text(R.id.preview_error).contains("not found"))
        assertTrue(!v.visible(R.id.preview_approve)) // approving a vanished issue can only fail
        assertTrue(v.text(R.id.preview_title).startsWith("Weekly digest email"))
    }

    @Test fun expiredTokenOffersSetUp() {
        val v = capture("11-8-preview-expired-token", FakePreviews.expiredToken(), heightDp = 360)
        assertEquals(context.getString(R.string.preview_set_up), v.text(R.id.preview_retry))
    }

    @Test fun darkMode() {
        val v = capture("11-9-preview-dark", FakePreviews.blockedWithClaudeLine(), host = { PreviewHost(context, night = true) })
        val bg = (v.background as? android.graphics.drawable.GradientDrawable)?.color?.defaultColor
        if (bg != null) assertTrue("sheet is dark in night mode", android.graphics.Color.luminance(bg) < 0.2f)
        assertTrue(v.visible(R.id.preview_claude))
        // The chip keeps readable text at night (it used to turn dark-on-dark).
        val chip = v.findViewById<TextView>(R.id.preview_status)
        assertTrue(contrast(chip.currentTextColor, chip.backgroundTintList!!.defaultColor) >= 4.5)
    }

    // --- task #43: Claude's full latest comment ---

    @Test fun longClaudeCommentCollapsed() {
        val v = capture("43-1-preview-claude-comment-collapsed", FakePreviews.blockedWithLongComment(), heightDp = 640)
        assertTrue(v.visible(R.id.preview_claude_need))
        assertTrue(v.text(R.id.preview_claude_need_text).startsWith("add a DEPLOY_KEY secret"))
        assertEquals(4, v.findViewById<TextView>(R.id.preview_claude_text).maxLines)
        assertEquals(context.getString(R.string.preview_show_more), v.text(R.id.preview_claude_toggle))
    }

    @Test fun longClaudeCommentExpanded() {
        val v = capture(
            "43-2-preview-claude-comment-expanded", FakePreviews.blockedWithLongComment(), heightDp = 760,
            prepare = { it.findViewById<View>(R.id.preview_claude_toggle).performClick() },
        )
        assertEquals(Int.MAX_VALUE, v.findViewById<TextView>(R.id.preview_claude_text).maxLines)
        assertEquals(context.getString(R.string.preview_show_less), v.text(R.id.preview_claude_toggle))
        assertTrue(v.text(R.id.preview_claude_text).contains("ready apart from deploying"))
    }

    @Test fun hebrewClaudeCommentRtl() {
        val v = capture("43-3-preview-claude-comment-hebrew", FakePreviews.hebrewBlockedComment(), heightDp = 640)
        assertTrue(v.text(R.id.preview_claude_need_text).startsWith("כתובת ה־API"))
        val body = v.findViewById<TextView>(R.id.preview_claude_text)
        assertTrue("Hebrew comment lays out right to left", body.layout.getParagraphDirection(0) == android.text.Layout.DIR_RIGHT_TO_LEFT)
    }

    @Test fun hebrewClaudeCommentDark() {
        val v = capture(
            "43-4-preview-claude-comment-hebrew-dark", FakePreviews.hebrewBlockedComment(), heightDp = 640,
            host = { PreviewHost(context, night = true) },
        )
        val need = v.findViewById<TextView>(R.id.preview_claude_need_text)
        assertTrue(contrast(need.currentTextColor, context.createConfigurationContext(nightConfig()).getColor(R.color.claude_need_bg)) >= 4.5)
    }

    private fun nightConfig() = android.content.res.Configuration(context.resources.configuration).apply {
        uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = android.graphics.Color.luminance(a).toDouble()
        val lb = android.graphics.Color.luminance(b).toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test fun smallScreenWithLargestFontStillReachesEveryButton() {
        val v = capture(
            "11-10-preview-small-screen-font-200", FakePreviews.todoLoaded(),
            widthDp = 300, heightDp = 460, host = { PreviewHost(context, fontScale = 2.0f) },
        )
        val frameHeight = (v.parent as View).height
        assertTrue("sheet fits the screen", v.height <= frameHeight)
        val scroll = v as android.widget.ScrollView
        val content = scroll.getChildAt(0)
        // Everything is either on screen or reachable by scrolling the sheet.
        assertTrue(content.height <= v.height || scroll.canScrollVertically(1))
        assertTrue(v.visible(R.id.preview_approve))
        assertTrue(v.visible(R.id.preview_open))
        assertEquals(4, v.findViewById<TextView>(R.id.preview_title).maxLines)
    }
}
