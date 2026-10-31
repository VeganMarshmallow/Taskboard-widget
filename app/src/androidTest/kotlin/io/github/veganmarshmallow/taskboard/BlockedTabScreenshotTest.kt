package io.github.veganmarshmallow.taskboard

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.TabTestViews.isHighlighted
import io.github.veganmarshmallow.taskboard.TabTestViews.shownTab
import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.board.TabSelection
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #34 screenshots: every blocked task is in Blocked, even with `needs-owner`; those rows keep the
 * needs-you badge. Each test also checks what its picture claims.
 */
@Screenshot
@RunWith(AndroidJUnit4::class)
class BlockedTabScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val state = WidgetState(FakeBoards.realistic(), null, FakeBoards.NOW)

    private fun captureWidget(name: String, state: WidgetState, tab: Section): View = ScreenshotCapture.widget(name, state, tab = tab)

    /** (badge, title) of each row the list shows; the badge is "" when hidden. */
    private fun View.rows(): List<Pair<String, String>> {
        val list = findViewById<ViewGroup>(R.id.board_list)
        return (0 until list.childCount).map { list.getChildAt(it) }.mapNotNull { row ->
            val title = row.findViewById<TextView>(R.id.item_text)?.text?.toString() ?: return@mapNotNull null
            val badge = row.findViewById<TextView>(R.id.item_badge)
            (if (badge.visibility == View.VISIBLE) badge.text.toString() else "") to title
        }
    }

    @Test fun blockedTabHoldsTheBlockedQuestionWithItsBadge() {
        val widget = captureWidget("34-1-blocked-tab-waiting-on-you", state, Section.BLOCKED)
        assertTrue(widget.isHighlighted(Section.BLOCKED))
        assertEquals("Blocked 2", widget.shownTab(Section.BLOCKED).text.toString())
        assertEquals("Needs you 1", widget.shownTab(Section.NEEDS_YOU).text.toString())
        // High priority + waiting on the owner: "!?"; the plain blocked task has no badge.
        assertEquals(
            listOf("!?" to "Which charting library for the dashboard?", "" to "Deploy preview builds to Vercel"),
            widget.rows(),
        )
    }

    @Test fun needsYouKeepsOnlyReviewsAndOpenQuestions() {
        val widget = captureWidget("34-2-needs-you-without-blocked", state, TabSelection.default(state.board))
        assertTrue(widget.isHighlighted(Section.NEEDS_YOU)) // a PR is waiting, so Needs you is still the default
        assertEquals(listOf("PR" to "Add dark mode to the portfolio site"), widget.rows())
    }

    @Test fun onlyABlockedQuestionOpensOnTheBlockedTab() {
        fun issue(n: Int, title: String, vararg labels: String) = Issue(n, title, labels.map(Labels::normalize).toSet(), true, null)
        val board = BoardBuilder().build(
            listOf(
                issue(30, "Which payment provider?", Labels.BLOCKED, Labels.NEEDS_OWNER),
                issue(31, "Waiting on the API key rotation", Labels.BLOCKED),
                issue(32, "Landing page copy", Labels.TODO),
            ),
            FakeBoards.NOW,
        )
        val only = WidgetState(board, null, FakeBoards.NOW)
        assertEquals(Section.BLOCKED, TabSelection.default(board))
        val widget = captureWidget("34-3-default-blocked-when-it-waits-on-you", only, TabSelection.default(board))
        assertTrue(widget.isHighlighted(Section.BLOCKED))
        assertEquals("Needs you 0", widget.shownTab(Section.NEEDS_YOU).text.toString())
        assertEquals("Blocked 2", widget.shownTab(Section.BLOCKED).text.toString())
        assertEquals(listOf("!?" to "Which payment provider?", "" to "Waiting on the API key rotation"), widget.rows())
    }

    @Test fun previewSaysBlockedWaitingOnYou() {
        lateinit var status: String
        instrumentation.runOnMainSync {
            val rendered = PreviewHost(context).render(FakePreviews.blockedWithClaudeLine(), widthDp = 380, heightDp = 420)
            status = rendered.sheet.findViewById<TextView>(R.id.preview_status).text.toString()
            Screenshots.save(context, "34-4-preview-blocked-waiting-on-you", WidgetHost.toBitmap(rendered.root))
        }
        assertEquals("Blocked: waiting on you", status)
    }
}
