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
@RunWith(AndroidJUnit4::class)
class TagPillsScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun capture(name: String, screen: PreviewScreen, widthDp: Int = 380, heightDp: Int = 440): FlowLayout {
        lateinit var tags: FlowLayout
        instrumentation.runOnMainSync {
            val rendered = PreviewHost(context).render(screen, widthDp = widthDp, heightDp = heightDp)
            tags = rendered.sheet.findViewById(R.id.preview_tags)
            Screenshots.save(context, name, WidgetHost.toBitmap(rendered.root))
        }
        return tags
    }

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
}
