package io.github.veganmarshmallow.taskboard

import android.app.Activity
import android.app.job.JobScheduler
import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.board.BoardLoader
import io.github.veganmarshmallow.taskboard.core.board.MergeLane
import io.github.veganmarshmallow.taskboard.core.board.MergeWait
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.board.withMergeLanes
import io.github.veganmarshmallow.taskboard.core.merge.MergeLaneLoader
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.data.TestOverrides
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.widget.MergeLaneViews
import io.github.veganmarshmallow.taskboard.widget.RowAction
import io.github.veganmarshmallow.taskboard.widget.RowActionActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.ZoneOffset

/**
 * Merge next (#45) on a device: a refresh against a fake GitHub over real HTTP ranks the PRs
 * waiting for a merge, the rows show one strong Merge and muted ones with reasons, and a muted tap
 * only says which PR to merge first. Also the PR screenshots.
 */
@Screenshot
@RunWith(AndroidJUnit4::class)
class MergeNextTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var server: FakeGitHubServer? = null

    @Before @After fun reset() {
        server?.close()
        server = null
        context.getSystemService(JobScheduler::class.java).cancelAll()
        TestOverrides.httpClient = null
        TokenStore(context).clear()
        StateStore(context).save(WidgetState.INITIAL)
    }

    private fun refresh(): WidgetState {
        val s = FakeGitHubServer(::route)
        server = s
        TestOverrides.httpClient = s::client
        TokenStore(context).save("github_pat_TESTONLY")
        return BoardLoader(Clock.fixed(FakeBoards.NOW, ZoneOffset.UTC), mergeLanes = MergeLaneLoader())
            .refresh(WidgetState.INITIAL, AppGraph.apiOrNull(context))
    }

    private fun lanes(state: WidgetState): Map<Int, MergeLane?> =
        state.board!!.section(Section.NEEDS_YOU).items.associate { it.number to it.mergeLane }

    private fun rows(widget: View): List<View> {
        val list = widget.findViewById<LinearLayout>(R.id.board_list)
        return (0 until list.childCount).map { list.getChildAt(it) }
    }

    private fun View.textOf(id: Int) = findViewById<TextView>(id).text.toString()
    private fun View.shows(id: Int) = findViewById<View>(id)?.visibility == View.VISIBLE

    @Test fun refreshHighlightsThePrReadyFirst() {
        val state = refresh()
        assertNull(state.error)
        val lanes = lanes(state)
        assertEquals("PR #31 passed first", MergeLane.NEXT, lanes[45])
        assertEquals(MergeLane(false, MergeWait.QUEUED, TITLE_45), lanes[44])
        assertEquals(MergeLane(false, MergeWait.CHECKS_RUNNING, TITLE_45), lanes[43])
        assertEquals(MergeLane(false, MergeWait.STACKED, TITLE_45), lanes[42])
        assertEquals(MergeLane(false, MergeWait.CONFLICT_CLAUDE_FIXES, TITLE_45), lanes[41])
        val s = server!!
        assertEquals("one PR list per repo", 1, s.received.count { it.path.startsWith("$REPO/pulls?") })
        assertEquals("compare only for PRs that passed the other checks", 2, s.received.count { it.path.startsWith("$REPO/compare/") })

        lateinit var widget: View
        instrumentation.runOnMainSync {
            val rendered = WidgetHost(context).render(state, 340, 340, Section.NEEDS_YOU)
            widget = rendered.widget
            Screenshots.save(context, "45-1-merge-next-rows", WidgetHost.toBitmap(rendered.root))
        }
        val byTitle = rows(widget).associateBy { it.textOf(R.id.item_text) }
        val next = byTitle.getValue(TITLE_45)
        assertTrue(next.shows(R.id.item_merge))
        assertTrue("the PR to merge next has no reason beside it", !next.shows(R.id.item_merge_note))
        val reasons = byTitle.filterKeys { it != TITLE_45 }.values.map { it.textOf(R.id.item_merge_note) }.toSet()
        assertEquals(setOf("ready", "checks running", "stacked", "conflict: Claude will fix"), reasons)
    }

    @Test fun noneReadyDark() {
        fun lane(wait: MergeWait) = MergeLane(false, wait, null)
        val base = FakeMerges.boardState()
        val board = base.board!!.withMergeLanes(mapOf(30 to lane(MergeWait.UPDATING), 29 to lane(MergeWait.CHECKS_FAILED)))
        val state = base.copy(board = board)
        val config = Configuration(context.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        val night = context.createConfigurationContext(config)
        lateinit var widget: View
        instrumentation.runOnMainSync {
            val rendered = WidgetHost(night).render(state, 340, 260, Section.NEEDS_YOU)
            widget = rendered.widget
            Screenshots.save(context, "45-2-none-ready-dark", WidgetHost.toBitmap(rendered.root))
        }
        val notes = rows(widget).filter { it.shows(R.id.item_merge) }.map { it.textOf(R.id.item_merge_note) }.toSet()
        assertEquals(setOf("updating", "checks failed"), notes)
    }

    @Test fun boardSavedBeforeMergeNextShowsPlainMergeButtons() {
        val state = FakeMerges.boardState() // no lanes, as decoded from a pre-#45 install
        lateinit var widget: View
        instrumentation.runOnMainSync { widget = WidgetHost(context).render(state, 340, 260, Section.NEEDS_YOU).widget }
        val merges = rows(widget).filter { it.shows(R.id.item_merge) }
        assertTrue(merges.isNotEmpty())
        val strong = context.getColor(R.color.status_text)
        merges.forEach {
            assertEquals("strong text, as before #45", strong, it.findViewById<TextView>(R.id.item_merge).currentTextColor)
            assertTrue("no reason", !it.shows(R.id.item_merge_note))
        }
    }

    @Test fun mutedTapOnlySaysWhichToMergeFirst() {
        assertEquals("Merge $TITLE_45 first", MergeLaneViews.mergeFirst(context, TITLE_45))
        val parsed = RowAction.from(RowAction.Merge(44, TITLE_45).toFillIn())
        assertEquals(RowAction.Merge(44, TITLE_45), parsed)
        assertEquals("the merge-next row merges as before", RowAction.Merge(45), RowAction.from(RowAction.Merge(45).toFillIn()))

        val monitor = instrumentation.addMonitor(RowActionActivity::class.java.name, null, false)
        val activity: Activity = try {
            context.startActivity(RowAction.Merge(44, TITLE_45).toFillIn().setClass(context, RowActionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            monitor.waitForActivityWithTimeout(10_000).also { assertNotNull("activity launched", it) }!!
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!activity.isFinishing && !activity.isDestroyed && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("the trampoline closes without a merge dialog", activity.isFinishing || activity.isDestroyed)
        assertNull("no merge flow started", (activity as RowActionActivity).mergeFlow)
    }

    private companion object {
        const val BOARD = "/repos/VeganMarshmallow/claude-workspace"
        const val REPO = "/repos/VeganMarshmallow/Taskboard-widget"
        const val TITLE_45 = "Merge next: highlight the PR to merge first"
        const val SHA_31 = "3131313131313131313131313131313131313131"
        const val SHA_32 = "3232323232323232323232323232323232323232"
        const val SHA_33 = "3333333333333333333333333333333333333333"

        val TASKS = listOf(
            45 to TITLE_45,
            44 to "Widget: show the build number in the header",
            43 to "Preview: collapse long Claude comments",
            42 to "Tabs: remember the last tab per widget",
            41 to "Setup: test the token against each project repo",
        )

        fun task(n: Int, title: String) =
            """{"number":$n,"title":"$title","state":"open","labels":[{"name":"status: in-review"}],"body":"### Repo\n\nVeganMarshmallow/Taskboard-widget"}"""

        fun pull(n: Int, task: Int, sha: String, base: String = "main", labels: String = "") =
            """{"number":$n,"title":"PR $n","state":"open","draft":false,"merged":false,"mergeable":null,""" +
                """"body":"Closes VeganMarshmallow/claude-workspace#$task","base":{"ref":"$base","repo":{"default_branch":"main"}},""" +
                """"head":{"ref":"claude/$task-x","sha":"$sha"},"labels":[$labels]}"""

        fun detail(n: Int, task: Int, sha: String) = pull(n, task, sha).replace("\"mergeable\":null", "\"mergeable\":true")

        fun runs(sha: String, status: String, conclusion: String?, updated: String) =
            """{"total_count":1,"workflow_runs":[{"id":${sha.take(4)},"workflow_id":7,"name":"build","head_sha":"$sha","run_number":9,""" +
                """"run_attempt":1,"event":"workflow_dispatch","created_at":"2026-10-30T09:00:00Z","updated_at":"$updated",""" +
                """"status":"$status","conclusion":${conclusion?.let { "\"$it\"" } ?: "null"}}]}"""

        /**
         * #45 (PR #31) and #44 (PR #32) are both ready, #31's checks finished first; #43's are
         * still running; #42 is stacked on #43's branch; #41 has `needs-rebase`.
         */
        fun route(method: String, path: String): FakeGitHubServer.Answer? = when {
            method != "GET" -> null
            path.startsWith("$BOARD/issues?") && "state=open" in path ->
                FakeGitHubServer.Answer(200, TASKS.joinToString(",", "[", "]") { (n, t) -> task(n, t) })
            path.startsWith("$BOARD/issues?") -> FakeGitHubServer.Answer(200, "[]")
            path.startsWith("$REPO/pulls?state=open") -> FakeGitHubServer.Answer(
                200,
                listOf(
                    pull(31, 45, SHA_31), pull(32, 44, SHA_32), pull(33, 43, SHA_33),
                    pull(34, 42, SHA_33, base = "claude/43-x"), pull(35, 41, SHA_33, labels = """{"name":"needs-rebase"}"""),
                ).joinToString(",", "[", "]"),
            )
            path == "$REPO/pulls/31" -> FakeGitHubServer.Answer(200, detail(31, 45, SHA_31))
            path == "$REPO/pulls/32" -> FakeGitHubServer.Answer(200, detail(32, 44, SHA_32))
            path == "$REPO/pulls/33" -> FakeGitHubServer.Answer(200, detail(33, 43, SHA_33))
            path.startsWith("$REPO/actions/runs?head_sha=$SHA_31") -> FakeGitHubServer.Answer(200, runs(SHA_31, "completed", "success", "2026-10-30T09:20:00Z"))
            path.startsWith("$REPO/actions/runs?head_sha=$SHA_32") -> FakeGitHubServer.Answer(200, runs(SHA_32, "completed", "success", "2026-10-30T09:40:00Z"))
            path.startsWith("$REPO/actions/runs?head_sha=$SHA_33") -> FakeGitHubServer.Answer(200, runs(SHA_33, "in_progress", null, "2026-10-30T09:10:00Z"))
            path.startsWith("$REPO/compare/main...") -> FakeGitHubServer.Answer(200, """{"behind_by":0,"ahead_by":3}""")
            else -> null
        }
    }
}
