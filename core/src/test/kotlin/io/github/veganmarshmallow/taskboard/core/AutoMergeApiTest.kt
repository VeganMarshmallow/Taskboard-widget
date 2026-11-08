package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.automerge.AutoMergeOutcome
import io.github.veganmarshmallow.taskboard.core.automerge.AutoMergeOutcome.Skip
import io.github.veganmarshmallow.taskboard.core.automerge.AutoMerger
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import io.github.veganmarshmallow.taskboard.core.merge.PullRef
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllPlanning
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStop
import io.github.veganmarshmallow.taskboard.core.mergeall.PlannedMerge
import io.github.veganmarshmallow.taskboard.core.mergeall.PullsMergeAllGitHub
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.pace.WorkPace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Auto merge (#79) end to end over the real GitHub client and JSON parsing (reviewer, #79): the same
 * fake GitHub as the instrumented `AutoMergeJobTest`, so its script is known to drive the real calls.
 * Two ready PRs: #31 (task 45, checks done first) and #32 (task 44).
 */
class AutoMergeApiTest {
    private class Repo31(
        var mergeStatus: Int = 200,
        var mergeBody: String = """{"sha":"abc","merged":true,"message":"Pull Request successfully merged"}""",
        var labels31: String = "[]",
        var draft31: Boolean = false,
        var base31: String = "main",
        var run31: String = run(SHA_31, "2026-11-07T09:20:00Z"),
        var behind31: Int = 0,
        var headNow31: String = SHA_31,
        var mergeThrows: Boolean = false,
        var mergedAfterPut: Boolean = false,
        /** Reads of PR #31 that still see [SHA_31]; later ones see [headNow31]. */
        var readsBeforeMove: Int = 0,
        /** How far #32 is behind once #31 is merged (#83: Faster merges it anyway, it was ready when the poll began). */
        var behind32AfterMerge: Int = 0,
    ) {
        var merged31 = false
        private var reads31 = 0

        fun answer(r: HttpRequest): HttpResponse {
            val path = r.url.removePrefix("https://api.github.com")
            return when {
                r.method == "PUT" && path == "$REPO/pulls/31/merge" -> {
                    if (mergedAfterPut) merged31 = true
                    if (mergeThrows) throw IOException("connection reset")
                    if (mergeStatus == 200) merged31 = true
                    status(mergeStatus, body = mergeBody)
                }
                r.method == "PUT" && path == "$REPO/pulls/32/merge" -> ok("""{"sha":"abd","merged":true,"message":"Pull Request successfully merged"}""")
                r.method != "GET" -> throw IOException("unexpected ${r.method} $path")
                path.startsWith("$BOARD/issues?") && "state=open" in path -> ok("[${task(45, TITLE_45)},${task(44, "Widget: show the build number")}]")
                path.startsWith("$BOARD/issues?") -> ok("[]")
                path.startsWith("$REPO/pulls?state=open") -> ok("[${pull31(SHA_31)},${pull(32, 44, SHA_32)}]")
                path == "$REPO/pulls/31" -> ok(pull31(if (reads31++ < readsBeforeMove) SHA_31 else headNow31, mergeable = "true"))
                path == "$REPO/pulls/32" -> ok(pull(32, 44, SHA_32, mergeable = "true"))
                path.startsWith("$REPO/actions/runs?head_sha=$SHA_31") -> ok(runsOf(run31))
                path.startsWith("$REPO/actions/runs?head_sha=$SHA_32") -> ok(runsOf(run(SHA_32, "2026-11-07T09:40:00Z")))
                path.startsWith("$REPO/compare/main...$SHA_31") -> ok("""{"behind_by":$behind31,"ahead_by":3}""")
                path.startsWith("$REPO/compare/main...$SHA_32") -> ok("""{"behind_by":${if (merged31) behind32AfterMerge else 0},"ahead_by":3}""")
                path.startsWith("$REPO/compare/main...") -> ok("""{"behind_by":0,"ahead_by":3}""")
                else -> throw IOException("no route for $path")
            }
        }

        private fun pull31(sha: String, mergeable: String = "null") =
            pull(31, 45, sha, mergeable, labels = labels31, draft = draft31, base = base31, merged = merged31)
    }

    private val gh = Repo31()
    private val http = FakeHttp().on({ true }, gh::answer)
    private val api = GitHubApi(http, "github_pat_TESTONLY")
    private val merger = AutoMerger({ MergeAllPlanning(api).candidates() }, PullsMergeAllGitHub(api) {})
    private fun poll() = merger.poll(enabled = true, mergeAllRunning = false)
    private fun writes() = http.requests.filter { it.method != "GET" }.map { "${it.method} ${it.url.removePrefix("https://api.github.com")}" }

    private val merged31 = AutoMergeOutcome.Merged(PlannedMerge(45, TITLE_45, PullRef(FakeMergeRepo, 31)))

    @Test fun mergesOnlyTheNextReadyPrPinnedToItsHead() {
        assertEquals(merged31, poll())
        assertEquals(listOf("PUT $REPO/pulls/31/merge"), writes())
        val put = http.requests.single { it.method == "PUT" }
        assertTrue("pinned to the checked head", put.body!!.contains(SHA_31))
    }

    @Test fun aPrLabelledNeedsRebaseIsPassedOverForTheNextReadyOne() {
        gh.labels31 = """[{"name":"needs-rebase"}]"""
        poll()
        assertEquals(listOf("PUT $REPO/pulls/32/merge"), writes())
    }

    @Test fun aDraftIsNeverMerged() {
        gh.draft31 = true
        poll()
        assertEquals(listOf("PUT $REPO/pulls/32/merge"), writes())
    }

    @Test fun aStackedPrIsNeverMerged() {
        gh.base31 = "claude/40-base"
        poll()
        assertTrue(writes().none { "/pulls/31/" in it })
    }

    @Test fun onlyAPartialRunIsNotEnough() {
        gh.run31 = run(SHA_31, "2026-11-07T09:20:00Z", title = "Auto merge [partial]")
        poll()
        assertTrue("#31 has no full run: not merged", writes().none { "/pulls/31/" in it })
    }

    @Test fun aRunHeldForApprovalIsNotMerged() {
        gh.run31 = run(SHA_31, "2026-11-07T09:20:00Z", conclusion = "action_required")
        poll()
        assertTrue(writes().none { "/pulls/31/" in it })
    }

    @Test fun behindItsDefaultBranchWaitsQuietly() {
        gh.behind31 = 1
        // The listing check already sees it behind (not ready), so #32 is the next one.
        poll()
        assertTrue(writes().none { "/pulls/31/" in it })
    }

    @Test fun theHeadMovedBetweenTheCheckAndTheMergeIsAQuietSkip() {
        gh.headNow31 = "9".repeat(40)
        for (seen in 1..3) {
            val reads = http.requests.size
            gh.readsBeforeMove = http.requests.count { it.url.endsWith("$REPO/pulls/31") } + seen
            val outcome = poll()
            assertTrue("$seen: $outcome", outcome == AutoMergeOutcome.Skipped(Skip.NOT_READY_YET) || (outcome as AutoMergeOutcome.Merged).merge.pr.number == 32)
            assertTrue("never merged #31 at a head it didn't check", http.requests.drop(reads).none { it.method == "PUT" && "/pulls/31/" in it.url })
        }
    }

    @Test fun mergeAnswersAreSortedIntoQuietSkipsAndFailures() {
        fun outcome(status: Int, message: String): AutoMergeOutcome {
            val g = Repo31(mergeStatus = status, mergeBody = """{"message":"$message"}""")
            val a = GitHubApi(FakeHttp().on({ true }, g::answer), "t")
            return AutoMerger({ MergeAllPlanning(a).candidates() }, PullsMergeAllGitHub(a) {}).poll(true, false)
        }
        assertEquals(Skip.NOT_READY_YET, (outcome(409, "Head branch was modified. Review and try the merge again.") as AutoMergeOutcome.Skipped).why)
        assertEquals(MergeAllStop.Kind.CONFLICT, (outcome(405, "Pull Request is not mergeable") as AutoMergeOutcome.Failed).stop.kind)
        assertEquals(MergeAllStop.Kind.NO_ACCESS, (outcome(403, "Resource not accessible by personal access token") as AutoMergeOutcome.Failed).stop.kind)
        assertEquals(MergeAllStop.Kind.ERROR, (outcome(422, "Validation failed") as AutoMergeOutcome.Failed).stop.kind)
        assertEquals(Skip.NOT_READY_YET, (outcome(502, "Bad gateway") as AutoMergeOutcome.Skipped).why)
        assertEquals(Skip.NOT_READY_YET, (outcome(405, "Pull Request is already merged") as AutoMergeOutcome.Skipped).why)
    }

    @Test fun aNetworkErrorAtTheMergeIsQuietUnlessItWentThrough() {
        gh.mergeThrows = true
        assertEquals(AutoMergeOutcome.Skipped(Skip.NOT_READY_YET), poll())
        gh.mergedAfterPut = true
        assertEquals("the re-read shows it merged", merged31, poll())
    }

    // --- #83, work pace ---

    private fun pollAt(pace: WorkPace) = AutoMerger({ MergeAllPlanning(api).candidates() }, PullsMergeAllGitHub(api, pace) {}, pace).pollAll(true, false)
    private val merged32 = AutoMergeOutcome.Merged(PlannedMerge(44, "Widget: show the build number", PullRef(FakeMergeRepo, 32)))
    private fun mergeBodies() = http.requests.filter { it.method == "PUT" }.map { JsonParser.parse(it.body!!) as Map<*, *> }

    @Test fun saveUsageMergesOneAndLeavesGitHubsMessage() {
        assertEquals(listOf(merged31), pollAt(WorkPace.SAVE))
        assertNull(mergeBodies().single()["commit_message"])
    }

    @Test fun fasterMergesEveryReadyPrBackToBackWithItsTrailer() {
        assertEquals(listOf(merged31, merged32), pollAt(WorkPace.FAST))
        assertEquals(listOf("PUT $REPO/pulls/31/merge", "PUT $REPO/pulls/32/merge"), writes())
        assertEquals(listOf("PR 31\n\nWork-Pace: fast", "PR 32\n\nWork-Pace: fast"), mergeBodies().map { it["commit_message"] })
        assertEquals("still pinned to the checked head", SHA_31, mergeBodies().first()["sha"])
    }

    @Test fun fasterMergesAPrLeftBehindOnlyByThisPoll() {
        gh.behind32AfterMerge = 1
        assertEquals(listOf(merged31, merged32), pollAt(WorkPace.FAST))
    }

    @Test fun fasterStopsAtTheFirstPrThatDoesNotMerge() {
        gh.mergeStatus = 405
        gh.mergeBody = """{"message":"Pull Request is not mergeable"}"""
        val outcomes = pollAt(WorkPace.FAST)
        assertEquals(1, outcomes.size)
        assertEquals(MergeAllStop.Kind.CONFLICT, (outcomes.single() as AutoMergeOutcome.Failed).stop.kind)
        assertTrue(writes().none { "/pulls/32/" in it })
    }

    @Test fun fasterStillWaitsForAFirstPrThatIsBehind() {
        gh.behind31 = 1 // not ready at the listing: #32 goes first, and alone
        assertEquals(listOf(merged32), pollAt(WorkPace.FAST))
    }

    @Test fun noTokenAccessToTheBoardIsAQuietSkip() {
        val a = GitHubApi(FakeHttp().on({ true }) { status(401, body = """{"message":"Bad credentials"}""") }, "t")
        assertEquals(AutoMergeOutcome.Skipped(Skip.CANT_CHECK), AutoMerger({ MergeAllPlanning(a).candidates() }, PullsMergeAllGitHub(a) {}).poll(true, false))
    }

    @Test fun offlineIsAQuietSkip() {
        val a = GitHubApi(FakeHttp(), "t") // every request fails with an IOException
        assertEquals(AutoMergeOutcome.Skipped(Skip.CANT_CHECK), AutoMerger({ MergeAllPlanning(a).candidates() }, PullsMergeAllGitHub(a) {}).poll(true, false))
    }

    private companion object {
        val FakeMergeRepo = WIDGET
        const val BOARD = "/repos/VeganMarshmallow/claude-workspace"
        const val REPO = "/repos/VeganMarshmallow/Taskboard-widget"
        const val TITLE_45 = "Auto merge: merge the next ready PR"
        const val SHA_31 = "3131313131313131313131313131313131313131"
        const val SHA_32 = "3232323232323232323232323232323232323232"

        fun task(n: Int, title: String) =
            """{"number":$n,"title":"$title","state":"open","labels":[{"name":"status: in-review"}],"body":"### Repo\n\nVeganMarshmallow/Taskboard-widget"}"""

        fun pull(n: Int, task: Int, sha: String, mergeable: String = "null", labels: String = "[]", draft: Boolean = false, base: String = "main", merged: Boolean = false) =
            """{"number":$n,"title":"PR $n","state":"${if (merged) "closed" else "open"}","draft":$draft,"merged":$merged,"mergeable":$mergeable,""" +
                """"body":"Closes VeganMarshmallow/claude-workspace#$task","base":{"ref":"$base","repo":{"default_branch":"main"}},""" +
                """"head":{"ref":"claude/$task-x","sha":"$sha"},"labels":$labels}"""

        fun run(sha: String, updated: String, title: String = "Auto merge", conclusion: String = "success") =
            """{"id":${sha.take(4)},"workflow_id":7,"name":"build","display_title":"$title","head_sha":"$sha","run_number":9,""" +
                """"run_attempt":1,"event":"pull_request","created_at":"2026-11-07T09:00:00Z","updated_at":"$updated",""" +
                """"status":"completed","conclusion":"$conclusion"}"""

        fun runsOf(vararg runs: String) = """{"total_count":${runs.size},"workflow_runs":[${runs.joinToString(",")}]}"""
    }
}
