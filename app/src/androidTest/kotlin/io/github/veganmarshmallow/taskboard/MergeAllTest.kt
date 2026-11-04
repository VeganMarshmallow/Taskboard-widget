package io.github.veganmarshmallow.taskboard

import android.app.job.JobScheduler
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.board.MergeLane
import io.github.veganmarshmallow.taskboard.core.board.MergeWait
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.board.withMergeLanes
import io.github.veganmarshmallow.taskboard.core.merge.PullRef
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllEnd
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllPhase
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllState
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStop
import io.github.veganmarshmallow.taskboard.core.mergeall.PlannedMerge
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.data.TestOverrides
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.mergeall.MergeAllJob
import io.github.veganmarshmallow.taskboard.widget.RowAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Merge all (#57) on a device: a 3-PR run against a fake GitHub over real HTTP (PUT update-branch,
 * PATCH retarget, PUT merge pinned to the updated head), job after job as JobScheduler would run
 * them, and the widget's Merge all line in each state. Also the PR screenshots.
 */
@Screenshot
@RunWith(AndroidJUnit4::class)
class MergeAllTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var server: FakeGitHubServer? = null
    private val store get() = AppGraph.mergeAllStore(context)

    @Before @After fun reset() {
        server?.close()
        server = null
        context.getSystemService(JobScheduler::class.java).cancelAll()
        TestOverrides.httpClient = null
        TokenStore(context).clear()
        StateStore(context).save(WidgetState.INITIAL)
        store.clear()
    }

    @Test fun mergesThreePrsInOrder() {
        val repo = FakeRepo()
        val s = FakeGitHubServer(repo::route)
        server = s
        TestOverrides.httpClient = s::client
        TokenStore(context).save("github_pat_TESTONLY")
        store.save(MergeAllState.start("run-57", PLAN, Instant.now()))

        var jobs = 0
        while (MergeAllJob(context).run() != null) assertTrue("ends within 20 jobs", ++jobs < 20)

        val end = store.load()!!
        assertEquals(MergeAllEnd.Done, end.end)
        assertEquals(listOf(31, 34, 32), end.merged.map { it.ref.number })
        val writes = s.received.filter { it.method != "GET" }.map { "${it.method} ${it.path.removePrefix(REPO)}" }
        assertEquals(
            listOf(
                "PUT /pulls/31/merge",
                "PATCH /pulls/34", "PUT /pulls/34/update-branch", "PUT /pulls/34/merge",
                "PUT /pulls/32/update-branch", "PUT /pulls/32/merge",
            ),
            writes,
        )
        fun body(path: String) = s.received.single { it.method != "GET" && it.path == "$REPO$path" }.body
        assertTrue(body("/pulls/34").contains("\"base\":\"main\""))
        assertTrue(body("/pulls/34/update-branch").contains(SHA_34))
        assertTrue("merged at the updated head", body("/pulls/34/merge").contains(FakeRepo.updated(34)))
        assertTrue(body("/pulls/31/merge").contains(SHA_31))
    }

    @Test fun theWidgetLineOffersProgressAndResult() {
        val base = FakeMerges.boardState()
        val board = base.board!!.withMergeLanes(mapOf(30 to MergeLane.NEXT, 29 to MergeLane(false, MergeWait.QUEUED, FakeMerges.TITLE)))
        val state = base.copy(board = board)

        assertLine("2 PRs ready to merge", "Merge all", line(state, Section.NEEDS_YOU, "57-1-merge-all-offer"))
        assertNull("not offered on other tabs", line(state, Section.TODO))

        val running = MergeAllState.start("run-57", PLAN, Instant.now()).copy(index = 1, phase = MergeAllPhase.WAIT)
        store.save(running)
        assertLine("Merging 2 of 3… waiting for checks", "Cancel", line(state, Section.TODO, "57-2-merge-all-progress"))

        store.requestCancel("run-57")
        assertLine("Merging 2 of 3… waiting for checks · stopping after this step", null, line(state, Section.NEEDS_YOU))

        store.save(running.copy(end = MergeAllEnd.Stopped(1, MergeAllStop(MergeAllStop.Kind.CHECKS_FAILED, "build"))))
        assertLine("Stopped at Taskboard-widget #34: checks failed (build). Merged 0 of 3", "OK", line(state, Section.NEEDS_YOU, "57-3-merge-all-stopped"))
    }

    @Test fun theMergeAllTapReachesTheTrampoline() {
        assertEquals(RowAction.MergeAll, RowAction.from(RowAction.MergeAll.toFillIn()))
    }

    /** The line shows [text] and a button labelled [button] (null: no button). */
    private fun assertLine(text: String, button: String?, shown: Pair<String, String?>?) {
        assertEquals(text, shown?.first)
        assertEquals(button, shown?.second)
    }

    /** The Merge all line's text and button label as rendered on [tab]; null when the line is hidden. */
    private fun line(state: WidgetState, tab: Section, screenshot: String? = null): Pair<String, String?>? {
        lateinit var widget: View
        instrumentation.runOnMainSync {
            val rendered = WidgetHost(context).render(state, 340, 340, tab)
            widget = rendered.widget
            screenshot?.let { Screenshots.save(context, it, WidgetHost.toBitmap(rendered.root)) }
        }
        if (widget.findViewById<View>(R.id.merge_all_bar).visibility != View.VISIBLE) return null
        val button = widget.findViewById<TextView>(R.id.merge_all_button)
        return widget.findViewById<TextView>(R.id.merge_all_text).text.toString() to
            button.text.toString().takeIf { button.visibility == View.VISIBLE }
    }

    /**
     * Taskboard-widget with three PRs ready: #31 (task 45), #34 stacked on #31's branch (task 42),
     * and #32 (task 44). A merge leaves every open PR one commit behind; update-branch gives the PR a
     * new head that is up to date. Every head's GitHub Actions run passed.
     */
    private class FakeRepo {
        private val heads = mutableMapOf(31 to SHA_31, 34 to SHA_34, 32 to SHA_32)
        private val bases = mutableMapOf(31 to "main", 34 to "claude/45-x", 32 to "main")
        private val behind = mutableMapOf(31 to 0, 34 to 0, 32 to 0)
        private val merged = mutableSetOf<Int>()
        private val tasks = mapOf(31 to 45, 34 to 42, 32 to 44)

        @Synchronized fun route(method: String, path: String): FakeGitHubServer.Answer? {
            val pull = Regex("^$REPO/pulls/(\\d+)(/update-branch|/merge)?$").find(path)
            return when {
                path.startsWith("$BOARD/issues") -> FakeGitHubServer.Answer(200, "[]") // the refresh after the run
                pull != null -> pullRoute(method, pull.groupValues[1].toInt(), pull.groupValues[2])
                method == "GET" && path.startsWith("$REPO/compare/main...") -> {
                    val sha = path.substringAfter("...")
                    val n = heads.entries.single { it.value == sha }.key
                    FakeGitHubServer.Answer(200, """{"behind_by":${behind.getValue(n)},"ahead_by":1}""")
                }
                method == "GET" && path.startsWith("$REPO/actions/runs?head_sha=") -> {
                    val sha = path.substringAfter("head_sha=").substringBefore("&")
                    FakeGitHubServer.Answer(200, runs(sha))
                }
                else -> null
            }
        }

        private fun pullRoute(method: String, n: Int, action: String): FakeGitHubServer.Answer? = when {
            n !in heads -> null
            method == "GET" && action.isEmpty() -> FakeGitHubServer.Answer(200, json(n))
            method == "PATCH" && action.isEmpty() -> { bases[n] = "main"; FakeGitHubServer.Answer(200, json(n)) }
            method == "PUT" && action == "/update-branch" -> {
                heads[n] = updated(n)
                behind[n] = 0
                FakeGitHubServer.Answer(202, """{"message":"Updating pull request branch."}""")
            }
            method == "PUT" && action == "/merge" -> {
                merged += n
                heads.keys.filter { it !in merged }.forEach { behind[it] = behind.getValue(it) + 1 }
                FakeGitHubServer.Answer(200, """{"sha":"abc","merged":true,"message":"Pull Request successfully merged"}""")
            }
            else -> null
        }

        private fun json(n: Int) =
            """{"number":$n,"title":"PR $n","state":"${if (n in merged) "closed" else "open"}","draft":false,"merged":${n in merged},""" +
                """"mergeable":true,"body":"Closes VeganMarshmallow/claude-workspace#${tasks[n]}",""" +
                """"base":{"ref":"${bases[n]}","repo":{"default_branch":"main"}},"head":{"ref":"claude/${tasks[n]}-x","sha":"${heads[n]}"}}"""

        private fun runs(sha: String) =
            """{"total_count":1,"workflow_runs":[{"id":${sha.take(6).hashCode().toLong() and 0xFFFFFF},"workflow_id":7,"name":"build",""" +
                """"head_sha":"$sha","run_number":9,"run_attempt":1,"event":"workflow_dispatch","created_at":"2026-10-30T09:00:00Z",""" +
                """"updated_at":"2026-10-30T09:20:00Z","status":"completed","conclusion":"success"}]}"""

        companion object {
            fun updated(n: Int) = n.toString().padEnd(40, 'e')
        }
    }

    private companion object {
        const val BOARD = "/repos/VeganMarshmallow/claude-workspace"
        const val REPO = "/repos/VeganMarshmallow/Taskboard-widget"
        const val SHA_31 = "3131313131313131313131313131313131313131"
        const val SHA_32 = "3232323232323232323232323232323232323232"
        const val SHA_34 = "3434343434343434343434343434343434343434"

        val PLAN = listOf(31 to 45, 34 to 42, 32 to 44).map { (pr, task) ->
            PlannedMerge(task, "Task $task", PullRef(FakeMerges.WIDGET, pr))
        }
    }
}
