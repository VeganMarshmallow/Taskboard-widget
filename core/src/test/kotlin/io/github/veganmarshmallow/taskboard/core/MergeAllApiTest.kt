package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.json.JsonWriter
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.merge.MergeStep
import io.github.veganmarshmallow.taskboard.core.merge.PullRef
import io.github.veganmarshmallow.taskboard.core.merge.PullsApi
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStop
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStops
import io.github.veganmarshmallow.taskboard.core.mergeall.PullsMergeAllGitHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Merge all's GitHub calls (#57): update-branch, retarget, and the adapter over the Merge button's checks. */
class MergeAllApiTest {
    private val repo = "$API_REPOS/VeganMarshmallow/Taskboard-widget"
    private val http = FakeHttp()
    private val api = GitHubApi(http, "t")
    private val pulls = PullsApi(api)

    @Test fun updateBranchIsPinnedToTheHeadItRead() {
        http.on({ it.url == "$repo/pulls/12/update-branch" }) { status(202, body = """{"message":"Updating pull request branch."}""") }
        assertEquals(MergeStep.Ok(Unit), pulls.updateBranch(pr(12)))
        val sent = http.requests.single()
        assertEquals("PUT", sent.method)
        assertEquals(mapOf("expected_head_sha" to SHA_A), JsonParser.parse(sent.body!!))
    }

    @Test fun updateBranchRefusalsAreToldApart() {
        fun answer(code: Int, message: String): MergeProblem {
            val h = FakeHttp().on({ true }) { status(code, body = JsonWriter.write(mapOf("message" to message))) }
            return (PullsApi(GitHubApi(h, "t")).updateBranch(pr(12)) as MergeStep.Stop).problem
        }
        assertEquals(MergeProblem.HeadChanged(pr(12)), answer(422, "expected head sha didn't match current head ref."))
        assertEquals(MergeProblem.Conflicts(pr(12), "merge conflict between base and head"), answer(422, "merge conflict between base and head"))
        assertEquals(MergeProblem.HeadChanged(pr(12)), answer(422, "There are no new commits on the base branch."))
        assertEquals(MergeProblem.Conflicts(pr(12), null), answer(422, ""))
        assertEquals(MergeProblem.NoRepoAccess(WIDGET, pr(12)), answer(403, "Resource not accessible by personal access token"))
        assertEquals(MergeProblem.Failed(BoardError.Server(502)), answer(502, "Bad gateway"))
    }

    @Test fun retargetMovesTheBaseAndReturnsThePr() {
        http.on({ it.method == "PATCH" && it.url == "$repo/pulls/12" }) { ok(JsonWriter.write(pullJson(12, base = "main"))) }
        val moved = pulls.retarget(pr(12, base = "claude/11"), "main")
        assertEquals("main", (moved as MergeStep.Ok).value.baseRef)
        assertEquals(mapOf("base" to "main"), JsonParser.parse(http.requests.single().body!!))
    }

    @Test fun theAdapterFillsInTheDefaultBranchOnce() {
        http.on({ it.url == "$repo/pulls/12" }) { ok(JsonWriter.write(pullJson(12, defaultBranch = null))) }
            .on({ it.url == repo }) { ok("""{"default_branch":"trunk"}""") }
            .on({ "/compare/trunk..." in it.url }) { ok("""{"behind_by":3}""") }
        val github = PullsMergeAllGitHub(api) {}
        val pr = (github.pull(PullRef(WIDGET, 12)) as MergeStep.Ok).value
        assertEquals("trunk", pr.defaultBranch)
        assertEquals(MergeStep.Ok(3L), github.behindBy(pr.copy(defaultBranch = null)))
        github.pull(PullRef(WIDGET, 12))
        assertEquals(1, http.requests.count { it.url == repo })
    }

    @Test fun theAdapterUsesTheMergeButtonsChecks() {
        http.on({ it.url == "$repo/pulls/12" }) { ok(JsonWriter.write(pullJson(12))) }
            .on({ "/actions/runs?head_sha=" in it.url }) { ok(runsJson(wfRun("CI", conclusion = "failure"))) }
        val step = PullsMergeAllGitHub(api) {}.checks(pr(12))
        assertEquals(listOf("CI"), ((step as MergeStep.Stop).problem as MergeProblem.ChecksFailed).names)
    }

    @Test fun theAdapterRechecksBeforeMerging() {
        http.on({ it.method == "GET" && it.url == "$repo/pulls/12" }) { ok(JsonWriter.write(pullJson(12, sha = SHA_B))) }
        val step = PullsMergeAllGitHub(api) {}.merge(pr(12))
        assertEquals(MergeProblem.HeadChanged(pr(12)), (step as MergeStep.Stop).problem)
        assertTrue("no merge PUT for a changed PR", http.requests.none { it.method == "PUT" })
    }

    @Test fun problemsMapToStopReasons() {
        val p = pr(12)
        assertEquals(MergeAllStop.Kind.RATE_LIMITED, MergeAllStops.of(MergeProblem.Failed(BoardError.RateLimited(null))).kind)
        assertEquals(MergeAllStop.Kind.NETWORK, MergeAllStops.of(MergeProblem.Failed(BoardError.Unreachable)).kind)
        assertEquals(MergeAllStop.Kind.NO_TOKEN, MergeAllStops.of(MergeProblem.Failed(BoardError.NoToken)).kind)
        assertEquals(MergeAllStop.Kind.STACKED, MergeAllStops.of(MergeProblem.Stacked(p, 11)).kind)
        assertEquals(MergeAllStop.Kind.PR_CLOSED, MergeAllStops.of(MergeProblem.NotFound).kind)
        assertTrue(MergeAllStops.transient(MergeProblem.Failed(BoardError.Timeout)))
        assertTrue(!MergeAllStops.transient(MergeProblem.Failed(BoardError.RateLimited(null))))
        assertTrue(MergeAllStops.stillWaiting(MergeProblem.ChecksRunning(p, emptyList())))
        assertTrue(!MergeAllStops.stillWaiting(MergeProblem.ChecksFailed(p, emptyList())))
    }
}
