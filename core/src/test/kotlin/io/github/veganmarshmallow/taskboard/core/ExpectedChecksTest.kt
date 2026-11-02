package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import io.github.veganmarshmallow.taskboard.core.merge.ExpectedChecks
import io.github.veganmarshmallow.taskboard.core.merge.PullJsonParser
import io.github.veganmarshmallow.taskboard.core.merge.PullsApi
import io.github.veganmarshmallow.taskboard.core.merge.WorkflowRun
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** The calls behind expected workflows (#41): what they cost and what happens when they fail. */
class ExpectedChecksTest {
    private val repo = "$API_REPOS/VeganMarshmallow/Taskboard-widget"
    private val historyUrl = "$repo/actions/runs?branch=claude/12&event=pull_request&per_page=100"
    private val workflowsUrl = "$repo/actions/workflows?per_page=100"

    /** Fast (id 1) and Slow (id 2) both ran for the PR's earlier commit [SHA_B]. NOW is 12:00. */
    private inner class Script {
        var history: () -> HttpResponse = {
            ok(runsJson(wfRun("Fast", workflow = 1, sha = SHA_B, event = "pull_request"), wfRun("Slow", workflow = 2, sha = SHA_B, event = "pull_request")))
        }
        var workflows: () -> HttpResponse = { ok(workflowsJson("Fast" to "active", "Slow" to "active")) }
        val http = FakeHttp().on({ it.url == historyUrl }) { history() }.on({ it.url == workflowsUrl }) { workflows() }

        fun notStarted(vararg head: Map<String, Any?>): List<String> =
            ExpectedChecks(PullsApi(GitHubApi(http, TOKEN, FIXED_CLOCK))).notStarted(pr(12), runs(*head))

        fun calls(url: String) = http.requests.count { it.url == url }
    }

    private fun workflowsJson(vararg workflows: Pair<String, String>, total: Int = workflows.size): String =
        """{"total_count":$total,"workflows":[""" +
            workflows.mapIndexed { i, (name, state) -> """{"id":${i + 1},"name":"$name","state":"$state"}""" }.joinToString(",") + "]}"

    private fun runs(vararg runs: Map<String, Any?>): List<WorkflowRun> = PullJsonParser.parseWorkflowRuns(runsJson(*runs)).runs

    private fun fastOnHead(created: String = "2026-10-22T11:58:00Z", event: String = "pull_request") =
        wfRun("Fast", workflow = 1, event = event, createdAt = created)

    private fun slowOnHead(event: String = "pull_request") = wfRun("Slow", workflow = 2, event = event, createdAt = "2026-10-22T11:58:30Z")

    @Test fun theSlowWorkflowNotRegisteredYetIsWaitedFor() {
        val s = Script()
        assertEquals(listOf("Slow (not started)"), s.notStarted(fastOnHead()))
        assertEquals(1 to 1, s.calls(historyUrl) to s.calls(workflowsUrl))
    }

    @Test fun everyExpectedWorkflowPresentCostsOneCall() {
        val s = Script()
        assertEquals(emptyList<String>(), s.notStarted(fastOnHead(), slowOnHead()))
        assertEquals("no workflow list when nothing is missing", 1 to 0, s.calls(historyUrl) to s.calls(workflowsUrl))
    }

    @Test fun aPushRunOfTheSlowWorkflowIsNotItsPullRequestRun() =
        assertEquals(listOf("Slow (not started)"), Script().notStarted(fastOnHead(), slowOnHead(event = "push")))

    @Test fun anOldHeadMakesNoExtraCalls() {
        val s = Script()
        assertEquals(emptyList<String>(), s.notStarted(fastOnHead(created = "2026-10-22T11:49:00Z")))
        assertEquals(0 to 0, s.calls(historyUrl) to s.calls(workflowsUrl))
    }

    @Test fun aDisabledOrDeletedWorkflowIsNotWaitedFor() {
        listOf("disabled_manually", "deleted").forEach { state ->
            val s = Script().apply { workflows = { ok(workflowsJson("Fast" to "active", "Slow" to state)) } }
            assertEquals(state, emptyList<String>(), s.notStarted(fastOnHead()))
        }
        val removed = Script().apply { workflows = { ok(workflowsJson("Fast" to "active")) } }
        assertEquals("a workflow file removed from a complete list", emptyList<String>(), removed.notStarted(fastOnHead()))
    }

    @Test fun mainOnlyAndManualWorkflowsAreNotRequired() {
        val s = Script().apply {
            history = {
                ok(runsJson(wfRun("Deploy", workflow = 3, sha = SHA_B, event = "push"), wfRun("Release", workflow = 4, sha = SHA_B, event = "workflow_dispatch")))
            }
        }
        assertEquals(emptyList<String>(), s.notStarted(fastOnHead()))
    }

    @Test fun aNewPrOrANewWorkflowWithNoHistoryIsTheOldCheck() {
        val s = Script().apply { history = { ok(runsJson()) } }
        assertEquals(emptyList<String>(), s.notStarted(fastOnHead()))
    }

    @Test fun anUnreadableHistoryFallsBackToTheOldCheck() {
        listOf<() -> HttpResponse>({ status(500) }, { status(403) }, { ok("not json") }, { throw IOException("down") }).forEach { answer ->
            val s = Script().apply { history = answer }
            assertEquals(emptyList<String>(), s.notStarted(fastOnHead()))
            assertEquals(0, s.calls(workflowsUrl))
        }
    }

    @Test fun anUnreadableWorkflowListKeepsWaiting() {
        listOf<() -> HttpResponse>({ status(500) }, { status(404) }, { ok("{}") }, { throw IOException("down") }).forEach { answer ->
            val s = Script().apply { workflows = answer }
            assertEquals(listOf("Slow (not started)"), s.notStarted(fastOnHead()))
        }
    }

    @Test fun anUnsafeBranchNameMakesNoCall() {
        val s = Script()
        val head = runs(fastOnHead())
        val notStarted = ExpectedChecks(PullsApi(GitHubApi(s.http, TOKEN, FIXED_CLOCK))).notStarted(pr(12, head = "x&event=push"), head)
        assertEquals(emptyList<String>(), notStarted)
        assertEquals(0, s.http.requests.size)
    }
}
