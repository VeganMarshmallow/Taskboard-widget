package io.github.veganmarshmallow.taskboard

import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.preview.PreviewScreen
import io.github.veganmarshmallow.taskboard.preview.FlowLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Screenshots of the preview's tag pills (issue #13). Each also checks what the picture claims. */
@Screenshot
@RunWith(AndroidJUnit4::class)
class TagPillsScreenshotTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun capture(
        name: String,
        screen: PreviewScreen,
        widthDp: Int = 380,
        heightDp: Int = 440,
        host: () -> PreviewHost = { PreviewHost(context) },
    ): FlowLayout = ScreenshotCapture.preview(name, screen, widthDp, heightDp, host).findViewById(R.id.preview_tags)

    private fun FlowLayout.texts() = (0 until childCount).map { (getChildAt(it) as TextView).text.toString() }
    private fun FlowLayout.rows() = (0 until childCount).map { getChildAt(it).top }.distinct().size

    @Test fun pillsOnABlockedTask() {
        val tags = capture("13-1-pills-blocked", FakePreviews.blockedWithClaudeLine())
        assertEquals(listOf("portfolio-site", "Research", "High priority", "Approved", "Needs you"), tags.texts())
    }

    @Test fun manyPillsWrapOnANarrowScreen() {
        val tags = capture("13-2-pills-wrapping", FakePreviews.manyTags(), widthDp = 300, heightDp = 460)
        assertEquals(listOf("Taskboard-widget", "Feature", "Refactor", "Low priority", "Needs you", "By Claude"), tags.texts())
        assertTrue(tags.rows() >= 2)
    }

    @Test fun hebrewTaskInRtl() {
        val tags = capture("13-3-pills-hebrew", FakePreviews.hebrewInProgress())
        assertEquals(listOf("landing-page", "Feature", "Approved", "By Claude"), tags.texts())
    }

    @Test fun noTagsHidesTheRow() {
        val tags = capture("13-4-pills-none-while-loading", FakePreviews.cachedWhileLoading(), heightDp = 300)
        assertEquals(View.GONE, tags.visibility)
    }

    // --- added in review ---

    private fun contrast(a: Int, b: Int): Double {
        val la = android.graphics.Color.luminance(a).toDouble()
        val lb = android.graphics.Color.luminance(b).toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun FlowLayout.assertReadable() = (0 until childCount).map { getChildAt(it) as TextView }.forEach { pill ->
        val ratio = contrast(pill.currentTextColor, pill.backgroundTintList!!.defaultColor)
        assertTrue("${pill.text}: $ratio", ratio >= 4.5)
    }

    @Test fun darkModeKeepsLabelColoursReadable() {
        val tags = capture("13-5-pills-dark", FakePreviews.blockedWithClaudeLine(), host = { PreviewHost(context, night = true) })
        assertEquals(listOf("portfolio-site", "Research", "High priority", "Approved", "Needs you"), tags.texts())
        tags.assertReadable()
    }

    @Test fun longLabelsAtLargeFontFitTheRow() {
        val tags = capture(
            "13-6-pills-long-font-150", FakePreviews.longTags(), widthDp = 320, heightDp = 480,
            host = { PreviewHost(context, fontScale = 1.5f) },
        )
        assertEquals(3, tags.childCount)
        (0 until tags.childCount).map { tags.getChildAt(it) }.forEach { pill ->
            assertTrue("pill inside the row", pill.left >= 0 && pill.right <= tags.width)
        }
        assertTrue(tags.rows() >= 2)
        tags.assertReadable()
    }
}
