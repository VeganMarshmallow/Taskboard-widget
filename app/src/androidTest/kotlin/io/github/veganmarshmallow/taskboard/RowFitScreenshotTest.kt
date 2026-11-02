package io.github.veganmarshmallow.taskboard

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.TabTestViews.assertRowsFit
import io.github.veganmarshmallow.taskboard.TabTestViews.configured
import io.github.veganmarshmallow.taskboard.TabTestViews.rowFitProblems
import io.github.veganmarshmallow.taskboard.core.board.MergeLane
import io.github.veganmarshmallow.taskboard.core.board.MergeWait
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * #49: at the narrowest widget (180dp) and font scale 2.0 a row with a PR showed only "PR …", and Merge was cut at
 * the bottom. Narrow widths and big fonts now get the two-line row: the title keeps the first line, the button its own.
 */
@Screenshot
@RunWith(AndroidJUnit4::class)
class RowFitScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** Needs you: the PR to merge next, a muted one with the longest reason, and a question. */
    private val board: WidgetState = FakeMerges.boardState().let { base ->
        val lanes = mapOf(30 to MergeLane.NEXT, 29 to MergeLane(false, MergeWait.CONFLICT_CLAUDE_FIXES, FakeMerges.TITLE))
        base.copy(board = base.board!!.withMergeLanes(lanes))
    }

    private fun render(host: Context, widthDp: Int, tab: Section, state: WidgetState = board): View {
        lateinit var widget: View
        // Tall enough that every row gets its full height; a short widget clips the list, not the rows.
        instrumentation.runOnMainSync { widget = WidgetHost(host).render(state, widthDp, 700, tab).widget }
        return widget
    }

    private fun rows(widget: View): List<View> {
        val list = widget.findViewById<ViewGroup>(R.id.board_list)
        return (0 until list.childCount).map { list.getChildAt(it) }.filter { it.findViewById<View>(R.id.item_text) != null }
    }

    private fun View.shows(id: Int) = findViewById<View>(id)?.visibility == View.VISIBLE

    @Test fun rowsFitAtEveryWidthAndFontScale() {
        val problems = listOf(1f, 1.3f, 2f).flatMap { scale ->
            listOf(180, 250, 340).flatMap { width ->
                listOf(null, Locale("he")).flatMap { locale ->
                    val host = configured(context, fontScale = scale, locale = locale)
                    listOf(Section.NEEDS_YOU, Section.TODO).flatMap { tab ->
                        rowFitProblems(render(host, width, tab), "x$scale ${width}dp ${locale ?: "en"} $tab")
                    }
                }
            }
        }
        assertTrue(problems.joinToString("; "), problems.isEmpty())
    }

    @Test fun plainMergeButtonsWithoutLanesFit() {
        val problems = listOf(180, 340).flatMap { width ->
            rowFitProblems(render(configured(context, fontScale = 2f), width, Section.NEEDS_YOU, FakeMerges.boardState()), "no lanes ${width}dp")
        }
        assertTrue(problems.joinToString("; "), problems.isEmpty())
    }

    @Test fun minWidthFontTwo() {
        val host = configured(context, fontScale = 2f)
        val widget = ScreenshotCapture.widget("49-1-rows-180dp-font-2.0", board, widthDp = 180, heightDp = 620, tab = Section.NEEDS_YOU, host = host)
        assertRowsFit(widget, "shot 2.0")
        val merges = rows(widget).filter { it.shows(R.id.item_merge) }
        assertEquals("both PRs offer Merge", 2, merges.size)
        merges.forEach { row ->
            assertTrue("the button has a line of its own", row.shows(R.id.item_actions))
            assertTrue("the title gets the row's first line", row.findViewById<TextView>(R.id.item_text).width > row.width / 2)
        }
        // The question has no button, so its row takes no button line.
        rows(widget).filter { !it.shows(R.id.item_merge) && !it.shows(R.id.item_approve) }.forEach {
            assertTrue(!it.shows(R.id.item_actions))
        }
    }

    @Test fun minWidthNormalFont() {
        val widget = ScreenshotCapture.widget("49-2-rows-180dp-font-1.0", board, widthDp = 180, heightDp = 420, tab = Section.NEEDS_YOU)
        assertRowsFit(widget, "shot 180 1.0")
        assertNotNull("180dp gets the two-line row", rows(widget).first().findViewById<View>(R.id.item_actions))
    }

    @Test fun defaultSizeKeepsTheOneLineRow() {
        val widget = ScreenshotCapture.widget("49-3-rows-default", board, tab = Section.NEEDS_YOU)
        assertRowsFit(widget, "shot default")
        rows(widget).forEach { assertNull("340dp at font 1.0 keeps one line", it.findViewById<View>(R.id.item_actions)) }
    }
}
