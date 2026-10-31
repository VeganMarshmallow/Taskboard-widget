package io.github.veganmarshmallow.taskboard

import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.TabTestViews.assertHeaderFits
import io.github.veganmarshmallow.taskboard.TabTestViews.configured
import io.github.veganmarshmallow.taskboard.TabTestViews.headerFitProblems
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.update.UpdateStatus
import io.github.veganmarshmallow.taskboard.update.UpdateStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** #28: the header's + and refresh buttons (and the update dot) stay whole at the narrowest width and big fonts. */
@Screenshot
@RunWith(AndroidJUnit4::class)
class HeaderScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val board = WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW)
    private val scales = listOf(1f, 1.15f, 1.3f, 1.5f, 1.75f, 2f)

    @After fun cleanUp() {
        UpdateStore(context).save(UpdateStatus.NEVER)
    }

    private val minWidthDp: Int by lazy {
        val info = AppWidgetManager.getInstance(context).getInstalledProvidersForPackage(context.packageName, null).single()
        (info.minResizeWidth / context.resources.displayMetrics.density).toInt()
    }

    private fun render(host: Context, state: WidgetState, widthDp: Int): View {
        lateinit var widget: View
        instrumentation.runOnMainSync { widget = WidgetHost(host).render(state, widthDp, 300).widget }
        return widget
    }

    private fun problemsAtEveryScale(what: String, locale: Locale? = null): List<String> =
        scales.flatMap { scale ->
            val host = configured(context, fontScale = scale, locale = locale)
            listOf(minWidthDp, 250, 340).flatMap { width ->
                listOf("board" to board, "initial" to WidgetState.INITIAL).flatMap { (name, state) ->
                    headerFitProblems(render(host, state, width), "$what x$scale ${width}dp $name")
                }
            }
        }

    @Test fun buttonsFitAtMinWidthAndEveryFontScale() {
        val problems = problemsAtEveryScale("plain")
        assertTrue(problems.joinToString("; "), problems.isEmpty())
    }

    @Test fun buttonsFitWithTheUpdateDot() {
        UpdateStore(context).save(FakeUpdates.available())
        val problems = problemsAtEveryScale("dot")
        assertTrue(problems.joinToString("; "), problems.isEmpty())
        val widget = ScreenshotCapture.widget("28-3-header-180dp-font-2.0-update-dot", board, widthDp = minWidthDp, host = configured(context, fontScale = 2f))
        assertEquals(View.VISIBLE, widget.findViewById<View>(R.id.update_dot).visibility)
        assertHeaderFits(widget, "shot dot 2.0")
    }

    @Test fun buttonsFitInAnRtlLocale() {
        val problems = problemsAtEveryScale("rtl", Locale("he"))
        assertTrue(problems.joinToString("; "), problems.isEmpty())
    }

    @Test fun headerAtMinWidthNormalFont() {
        val widget = ScreenshotCapture.widget("28-1-header-180dp-font-1.0", board, widthDp = minWidthDp)
        assertHeaderFits(widget, "shot 1.0")
    }

    @Test fun headerAtMinWidthFontTwo() {
        val widget = ScreenshotCapture.widget("28-2-header-180dp-font-2.0", board, widthDp = minWidthDp, host = configured(context, fontScale = 2f))
        assertHeaderFits(widget, "shot 2.0")
        // The title gives way (ellipsized) instead of the buttons.
        val title = widget.findViewById<TextView>(R.id.title)
        assertTrue("title is ${title.width}px wide", title.width > 0)
    }

    @Test fun normalSizesLookAsBefore() {
        listOf(250, 340).forEach { width ->
            val widget = render(context, board, width)
            val title = widget.findViewById<TextView>(R.id.title)
            assertEquals("${width}dp title ellipsized", 0, title.layout.getEllipsisCount(0))
            assertTrue("${width}dp updated time collapsed", widget.findViewById<View>(R.id.updated).width > 0)
            assertHeaderFits(widget, "${width}dp")
        }
    }
}
