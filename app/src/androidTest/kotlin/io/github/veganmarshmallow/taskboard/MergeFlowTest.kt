package io.github.veganmarshmallow.taskboard

import android.app.Activity
import android.app.job.JobScheduler
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.merge.MergeDialog
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.merge.MergeService
import io.github.veganmarshmallow.taskboard.core.merge.MergeStep
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.merge.MergeFlow
import io.github.veganmarshmallow.taskboard.net.UrlConnectionHttpClient
import io.github.veganmarshmallow.taskboard.preview.TaskPreviewActivity
import io.github.veganmarshmallow.taskboard.widget.RowAction
import io.github.veganmarshmallow.taskboard.widget.RowActionActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Merging from the widget (task #30) on a device, against a fake GitHub server over real HTTP. */
@RunWith(AndroidJUnit4::class)
class MergeFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var server: FakeGitHubServer? = null

    @Before @After fun reset() {
        server?.close()
        server = null
        context.getSystemService(JobScheduler::class.java).cancelAll()
        AppGraph.httpClient = { UrlConnectionHttpClient() }
        TokenStore(context).clear()
        StateStore(context).save(WidgetState.INITIAL)
    }

    private fun serve(script: (String, String) -> FakeGitHubServer.Answer?): FakeGitHubServer {
        val s = FakeGitHubServer(script)
        server = s
        AppGraph.httpClient = s::client
        TokenStore(context).save("github_pat_TESTONLY")
        StateStore(context).save(FakeMerges.boardState())
        return s
    }

    private fun service() = MergeService(AppGraph.apiOrNull(context)!!, sleep = {})

    private fun waitUntil(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var ok = false
            instrumentation.runOnMainSync { ok = condition() }
            if (ok) return
            SystemClock.sleep(100)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun <T : Activity> launch(type: Class<T>, intent: Intent): T {
        val monitor = instrumentation.addMonitor(type.name, null, false)
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            @Suppress("UNCHECKED_CAST")
            return (monitor.waitForActivityWithTimeout(10_000) as T?).also { assertNotNull("activity launched", it) }!!
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    // --- core over real HTTP ---

    @Test fun findsChecksAndMergesOverHttp() {
        val s = serve(FakeMerges.script())
        val pr = (service().prepare(30) as MergeStep.Ok).value
        assertEquals(12, pr.number)
        assertEquals(FakeMerges.SHA, pr.headSha)
        assertTrue(service().merge(pr) is MergeStep.Ok)
        val put = s.received.single { it.method == "PUT" }
        assertEquals("/repos/VeganMarshmallow/Taskboard-widget/pulls/12/merge", put.path)
        assertTrue(put.body, put.body.contains("\"sha\":\"${FakeMerges.SHA}\""))
        assertTrue(put.body, put.body.contains("\"merge_method\":\"merge\""))
        assertEquals("Bearer github_pat_TESTONLY", put.authorization)
    }

    @Test fun stackedPrIsRefusedAndNeverMerged() {
        val s = serve(FakeMerges.script(stacked = true))
        val problem = (service().prepare(30) as MergeStep.Stop).problem as MergeProblem.Stacked
        assertEquals(11, problem.blocker)
        assertTrue(s.received.none { it.method == "PUT" })
    }

    @Test fun headChangedIs409() {
        serve(FakeMerges.script(mergeStatus = 409))
        val pr = (service().prepare(30) as MergeStep.Ok).value
        assertTrue((service().merge(pr) as MergeStep.Stop).problem is MergeProblem.HeadChanged)
    }

    // --- the real screens ---

    @Test fun previewMergeButtonConfirmsThenMerges() {
        val s = serve(FakeMerges.script())
        val activity = launch(TaskPreviewActivity::class.java, TaskPreviewActivity.intent(context, 30))
        try {
            waitUntil("Merge button") { activity.findViewById<View>(R.id.preview_merge).isShown }
            instrumentation.runOnMainSync { activity.findViewById<Button>(R.id.preview_merge).performClick() }
            waitUntil("confirmation") { activity.mergeFlow?.shown?.kind == MergeDialog.Kind.CONFIRM }
            var flow: MergeFlow? = null
            instrumentation.runOnMainSync { flow = activity.mergeFlow }
            assertEquals("Merge PR #12?", flow!!.shown!!.title)
            assertEquals(FakeMerges.TITLE, flow!!.shown!!.lines[0])
            assertEquals("VeganMarshmallow/Taskboard-widget #12", flow!!.shown!!.lines[1])
            assertTrue("nothing merged before confirming", s.received.none { it.method == "PUT" })
            instrumentation.runOnMainSync { clickPrimary(flow!!) }
            waitUntil("merged and closed") { activity.mergeFlow == null && activity.findViewById<View>(R.id.preview_merged).isShown }
            assertEquals(1, s.received.count { it.method == "PUT" })
            instrumentation.runOnMainSync { assertTrue(!activity.findViewById<View>(R.id.preview_merge).isShown) }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun rowMergeShowsTheStackedRefusal() {
        val s = serve(FakeMerges.script(stacked = true))
        val intent = RowAction.Merge(30).toFillIn().setClass(context, RowActionActivity::class.java)
        val activity = launch(RowActionActivity::class.java, intent)
        waitUntil("refusal") { activity.mergeFlow?.shown?.kind == MergeDialog.Kind.REFUSAL }
        var lines: List<String> = emptyList()
        instrumentation.runOnMainSync { lines = activity.mergeFlow!!.shown!!.lines }
        assertEquals("This PR is stacked on claude/11-first; merge #11 first.", lines[0])
        assertTrue(s.received.none { it.method == "PUT" })
        instrumentation.runOnMainSync { clickSecondary(activity.mergeFlow!!) } // Close
        waitUntil("trampoline finished") { activity.isFinishing || activity.isDestroyed }
    }

    @Test fun withoutATokenTheFlowOffersSetUpAndSendsNothing() {
        TokenStore(context).clear()
        val intent = RowAction.Merge(30).toFillIn().setClass(context, RowActionActivity::class.java)
        val activity = launch(RowActionActivity::class.java, intent)
        try {
            waitUntil("refusal") { activity.mergeFlow?.shown?.kind == MergeDialog.Kind.REFUSAL }
            instrumentation.runOnMainSync {
                assertEquals(context.getString(R.string.merge_set_up), dialogButton(activity.mergeFlow!!, R.id.merge_primary).text.toString())
            }
        } finally {
            instrumentation.runOnMainSync { activity.mergeFlow?.close() }
        }
    }

    @Test fun mergeActionExtrasRoundTripAndRejectBadNumbers() {
        assertEquals(RowAction.Merge(30), RowAction.from(RowAction.Merge(30).toFillIn()))
        assertEquals(null, RowAction.from(Intent().putExtra("row_kind", "merge").putExtra("row_issue", 0)))
        assertEquals(null, RowAction.from(Intent().putExtra("row_kind", "merge")))
    }

    private fun dialogButton(flow: MergeFlow, id: Int): Button = flow.dialogRootForTest()!!.findViewById(id)
    private fun clickPrimary(flow: MergeFlow) { dialogButton(flow, R.id.merge_primary).performClick() }
    private fun clickSecondary(flow: MergeFlow) { dialogButton(flow, R.id.merge_secondary).performClick() }
}
