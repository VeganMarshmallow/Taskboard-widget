package io.github.veganmarshmallow.taskboard

import android.app.job.JobScheduler
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.automerge.AutoMergeJob
import io.github.veganmarshmallow.taskboard.core.automerge.AutoMergeOutcome
import io.github.veganmarshmallow.taskboard.core.merge.PullRef
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllState
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStop
import io.github.veganmarshmallow.taskboard.core.mergeall.PlannedMerge
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.AutoMergePrefs
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.data.TestOverrides
import io.github.veganmarshmallow.taskboard.data.TokenStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Auto merge (#79) on a device: one poll against a fake GitHub over real HTTP. Two PRs are ready
 * (#31 finished its checks first); the poll merges #31 only, pinned to its head. Off, or while a
 * Merge all run goes on, it sends nothing; a refused merge is a failure, remembered once.
 */
@RunWith(AndroidJUnit4::class)
class AutoMergeJobTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var server: FakeGitHubServer? = null
    private val prefs get() = AutoMergePrefs(context)

    @Before @After fun reset() {
        server?.close()
        server = null
        context.getSystemService(JobScheduler::class.java).cancelAll()
        TestOverrides.httpClient = null
        TokenStore(context).clear()
        StateStore(context).save(WidgetState.INITIAL)
        AppGraph.mergeAllStore(context).clear()
        prefs.setEnabled(false)
    }

    @Test fun offByDefault() {
        context.getSharedPreferences("auto_merge", 0).edit().clear().commit()
        assertFalse(AutoMergePrefs(context).enabled())
    }

    @Test fun mergesOnlyTheNextReadyPr() {
        val s = serve()
        prefs.setEnabled(true)
        val outcome = AutoMergeJob(context).run()
        assertEquals(AutoMergeOutcome.Merged(PlannedMerge(45, TITLE_45, PullRef(FakeMerges.WIDGET, 31))), outcome)
        val writes = s.received.filter { it.method != "GET" }
        assertEquals(listOf("PUT $REPO/pulls/31/merge"), writes.map { "${it.method} ${it.path}" })
        assertTrue("merged at the checked head", writes.single().body.contains(SHA_31))
        assertTrue("with the owner's token", writes.single().authorization.orEmpty().contains("github_pat_TESTONLY"))
    }

    @Test fun offItSendsNothing() {
        val s = serve()
        assertEquals(AutoMergeOutcome.Skipped(AutoMergeOutcome.Skip.DISABLED), AutoMergeJob(context).run())
        assertTrue(s.received.isEmpty())
    }

    @Test fun waitsForARunningMergeAll() {
        val s = serve()
        prefs.setEnabled(true)
        AppGraph.mergeAllStore(context).save(MergeAllState.start("run-79", listOf(PlannedMerge(44, "Task 44", PullRef(FakeMerges.WIDGET, 32))), Instant.now()))
        assertEquals(AutoMergeOutcome.Skipped(AutoMergeOutcome.Skip.MERGE_ALL_RUNNING), AutoMergeJob(context).run())
        assertTrue(s.received.isEmpty())
    }

    @Test fun aRefusedMergeIsAFailureRememberedOnce() {
        serve(mergeStatus = 403)
        prefs.setEnabled(true)
        val failed = AutoMergeJob(context).run() as AutoMergeOutcome.Failed
        assertEquals(31, failed.merge.pr.number)
        assertEquals(MergeAllStop.Kind.NO_ACCESS, failed.stop.kind)
        assertEquals(failed.key, prefs.lastFailure())
        assertNotNull(AutoMergeJob(context).run() as? AutoMergeOutcome.Failed)
        assertEquals(failed.key, prefs.lastFailure())
    }

    private fun serve(mergeStatus: Int = 200): FakeGitHubServer {
        val s = FakeGitHubServer { method, path -> route(method, path, mergeStatus) }
        server = s
        TestOverrides.httpClient = s::client
        TokenStore(context).save("github_pat_TESTONLY")
        return s
    }

    private companion object {
        const val BOARD = "/repos/VeganMarshmallow/claude-workspace"
        const val REPO = "/repos/VeganMarshmallow/Taskboard-widget"
        const val TITLE_45 = "Auto merge: merge the next ready PR"
        const val SHA_31 = "3131313131313131313131313131313131313131"
        const val SHA_32 = "3232323232323232323232323232323232323232"

        fun task(n: Int, title: String) =
            """{"number":$n,"title":"$title","state":"open","labels":[{"name":"status: in-review"}],"body":"### Repo\n\nVeganMarshmallow/Taskboard-widget"}"""

        fun pull(n: Int, task: Int, sha: String, mergeable: String = "null") =
            """{"number":$n,"title":"PR $n","state":"open","draft":false,"merged":false,"mergeable":$mergeable,""" +
                """"body":"Closes VeganMarshmallow/claude-workspace#$task","base":{"ref":"main","repo":{"default_branch":"main"}},""" +
                """"head":{"ref":"claude/$task-x","sha":"$sha"},"labels":[]}"""

        fun runs(sha: String, updated: String) =
            """{"total_count":1,"workflow_runs":[{"id":${sha.take(4)},"workflow_id":7,"name":"build","head_sha":"$sha","run_number":9,""" +
                """"run_attempt":1,"event":"pull_request","created_at":"2026-11-07T09:00:00Z","updated_at":"$updated",""" +
                """"status":"completed","conclusion":"success"}]}"""

        /** #45 (PR #31) and #44 (PR #32) are both ready; #31's checks finished first. */
        fun route(method: String, path: String, mergeStatus: Int): FakeGitHubServer.Answer? = when {
            method == "PUT" && path == "$REPO/pulls/31/merge" -> if (mergeStatus == 200) {
                FakeGitHubServer.Answer(200, """{"sha":"abc","merged":true,"message":"Pull Request successfully merged"}""")
            } else {
                FakeGitHubServer.Answer(mergeStatus, """{"message":"Resource not accessible by personal access token"}""")
            }
            method != "GET" -> null
            path.startsWith("$BOARD/issues?") && "state=open" in path ->
                FakeGitHubServer.Answer(200, "[${task(45, TITLE_45)},${task(44, "Widget: show the build number")}]")
            path.startsWith("$BOARD/issues?") -> FakeGitHubServer.Answer(200, "[]")
            path.startsWith("$REPO/pulls?state=open") -> FakeGitHubServer.Answer(200, "[${pull(31, 45, SHA_31)},${pull(32, 44, SHA_32)}]")
            path == "$REPO/pulls/31" -> FakeGitHubServer.Answer(200, pull(31, 45, SHA_31, mergeable = "true"))
            path == "$REPO/pulls/32" -> FakeGitHubServer.Answer(200, pull(32, 44, SHA_32, mergeable = "true"))
            path.startsWith("$REPO/actions/runs?head_sha=$SHA_31") -> FakeGitHubServer.Answer(200, runs(SHA_31, "2026-11-07T09:20:00Z"))
            path.startsWith("$REPO/actions/runs?head_sha=$SHA_32") -> FakeGitHubServer.Answer(200, runs(SHA_32, "2026-11-07T09:40:00Z"))
            path.startsWith("$REPO/compare/main...") -> FakeGitHubServer.Answer(200, """{"behind_by":0,"ahead_by":3}""")
            else -> null
        }
    }
}
