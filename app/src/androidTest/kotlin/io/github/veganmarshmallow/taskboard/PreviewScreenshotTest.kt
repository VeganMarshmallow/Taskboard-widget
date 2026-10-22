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

    private fun capture(name: String, screen: PreviewScreen, heightDp: Int = 420): View {
        lateinit var sheet: View
        instrumentation.runOnMainSync {
            val rendered = PreviewHost(context).render(screen, heightDp = heightDp)
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
        assertTrue(v.text(R.id.preview_claude).startsWith("⛔ Blocked: Chart.js"))
        assertEquals("Needs you", v.text(R.id.preview_status))
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
        assertTrue(v.visible(R.id.preview_approve)) // the cached row could approve, nothing says otherwise
    }

    @Test fun justApproved() {
        val v = capture("11-6-preview-approved", FakePreviews.justApproved())
        assertTrue(v.visible(R.id.preview_approved))
        assertTrue(!v.visible(R.id.preview_approve))
    }
}
