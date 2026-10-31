package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.BoardLoader
import io.github.veganmarshmallow.taskboard.core.board.MergeLane
import io.github.veganmarshmallow.taskboard.core.board.MergeWait
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import io.github.veganmarshmallow.taskboard.core.json.JsonWriter
import io.github.veganmarshmallow.taskboard.core.merge.MergeLaneLoader
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetStateCodec
import io.github.veganmarshmallow.taskboard.core.work.TimeLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Duration

/** The merge-next step of a refresh (#45) against a scripted GitHub: lookups, checks, cost and failures. */
class MergeLanesTest {
    private val boardUrl = "$API_REPOS/VeganMarshmallow/claude-workspace"
    private val repo = "$API_REPOS/VeganMarshmallow/Taskboard-widget"
    private val repoLine = "### Repo\n\nVeganMarshmallow/Taskboard-widget"

    private fun task(n: Int, body: String? = repoLine, vararg labels: String = arrayOf("status: in-review")) =
        issueJson(n, *labels, title = "Task $n") + ("body" to body)

    private fun closes(n: Int) = "Closes VeganMarshmallow/claude-workspace#$n"

    /** Scripted GitHub. [pulls]: open PRs; [runs]/[behind]: per head SHA. */
    private inner class Script(
        val issues: List<Map<String, Any?>>,
        val pulls: List<Map<String, Any?>>,
        val runs: Map<String, String> = emptyMap(),
        val behind: Map<String, Long> = emptyMap(),
        val runsStatus: Int? = null,
    ) {
        val http = FakeHttp().on({ true }) { route(it.url) }

        private fun route(url: String): HttpResponse = when {
            url.startsWith("$boardUrl/issues?") && "state=open" in url -> ok(page(*issues.toTypedArray()))
            url.startsWith("$boardUrl/issues?") -> ok("[]")
            url.startsWith("$repo/pulls?state=open") -> ok(pullsPage(*pulls.toTypedArray()))
            url.matches(Regex("""$repo/pulls/\d+""")) -> pulls.firstOrNull { it["number"] == url.substringAfterLast('/').toLong() }
                ?.let { ok(JsonWriter.write(it)) } ?: status(404)
            url.startsWith("$repo/actions/runs?head_sha=") && runsStatus != null -> status(runsStatus)
            url.startsWith("$repo/actions/runs?head_sha=") -> ok(runs[url.substringAfter("head_sha=").substringBefore('&')] ?: runsJson())
            url.startsWith("$repo/compare/main...") -> behind[url.substringAfter("...")]?.let { ok("""{"behind_by":$it,"ahead_by":2}""") }
                ?: status(500)
            else -> throw IOException("no route for $url")
        }

        fun refresh(loader: MergeLaneLoader = MergeLaneLoader()): WidgetState =
            BoardLoader(FIXED_CLOCK, mergeLanes = loader).refresh(WidgetState.INITIAL, GitHubApi(http, TOKEN, FIXED_CLOCK))

        fun lanes(loader: MergeLaneLoader = MergeLaneLoader()): Map<Int, MergeLane?> =
            refresh(loader).board!!.section(Section.NEEDS_YOU).items.associate { it.number to it.mergeLane }

        fun count(part: String) = http.requests.count { part in it.url }
    }

    private val shaC = "cccccccccccccccccccccccccccccccccccccccc"
    private val shaD = "dddddddddddddddddddddddddddddddddddddddd"

    private fun doneRun(sha: String, updated: String) = wfRun(sha = sha) + ("updated_at" to updated)

    @Test fun readyFirstIsNextAndTheOthersSayWhy() {
        val s = Script(
            issues = listOf(task(30), task(29), task(28), task(27)),
            pulls = listOf(
                pullJson(12, body = closes(30), sha = SHA_A),
                pullJson(13, body = closes(29), sha = SHA_B),
                pullJson(14, body = closes(28), sha = shaC),
                pullJson(15, body = closes(27), base = "claude/14-branch"),
            ),
            runs = mapOf(
                SHA_A to runsJson(doneRun(SHA_A, "2026-10-30T10:40:00Z")),
                SHA_B to runsJson(doneRun(SHA_B, "2026-10-30T10:20:00Z")),
                shaC to runsJson(wfRun(sha = shaC, status = "in_progress", conclusion = null)),
            ),
            behind = mapOf(SHA_A to 0L, SHA_B to 0L),
        )
        val lanes = s.lanes()
        assertEquals("PR #13's checks finished first", MergeLane.NEXT, lanes[29])
        assertEquals(MergeLane(false, MergeWait.QUEUED, "Task 29"), lanes[30])
        assertEquals(MergeLane(false, MergeWait.CHECKS_RUNNING, "Task 29"), lanes[28])
        assertEquals(MergeLane(false, MergeWait.STACKED, "Task 29"), lanes[27])
        assertEquals("one PR list for the repo", 1, s.count("/pulls?state=open"))
        assertEquals("no compare for PRs that failed a cheaper check", 2, s.count("/compare/"))
        assertEquals("a stacked PR costs no request of its own", 0, s.count("/pulls/15"))
    }

    @Test fun behindMainIsUpdatingAndNothingIsNext() {
        val s = Script(listOf(task(30)), listOf(pullJson(12, body = closes(30))), mapOf(SHA_A to runsJson(passed())), mapOf(SHA_A to 3L))
        assertEquals(MergeLane(false, MergeWait.UPDATING, null), s.lanes()[30])
    }

    @Test fun needsRebaseLabelIsAConflictClaudeFixesWithoutRequests() {
        val pr = pullJson(12, body = closes(30)) + ("labels" to listOf(mapOf("name" to "needs-rebase")))
        val s = Script(listOf(task(30)), listOf(pr))
        assertEquals(MergeWait.CONFLICT_CLAUDE_FIXES, s.lanes()[30]?.wait)
        assertEquals(0, s.count("/pulls/12") + s.count("/actions/runs") + s.count("/compare/"))
    }

    @Test fun draftConflictAndUnknownMergeability() {
        val s = Script(
            listOf(task(30), task(29), task(28)),
            listOf(
                pullJson(12, body = closes(30), draft = true),
                pullJson(13, body = closes(29), mergeable = false),
                pullJson(14, body = closes(28), mergeable = null),
            ),
        )
        val lanes = s.lanes()
        assertEquals(MergeWait.DRAFT, lanes[30]?.wait)
        assertEquals(MergeWait.CONFLICT, lanes[29]?.wait)
        assertEquals(MergeWait.UPDATING, lanes[28]?.wait)
    }

    @Test fun failedAndMissingChecks() {
        val s = Script(
            listOf(task(30), task(29)),
            listOf(pullJson(12, body = closes(30), sha = SHA_A), pullJson(13, body = closes(29), sha = SHA_B)),
            mapOf(SHA_A to runsJson(wfRun(conclusion = "failure"))),
        )
        val lanes = s.lanes()
        assertEquals(MergeWait.CHECKS_FAILED, lanes[30]?.wait)
        assertEquals(MergeWait.NO_CHECKS, lanes[29]?.wait)
    }

    @Test fun dispatchedRunsOnTheNewHeadCount() {
        val dispatched = wfRun(sha = shaD, event = "workflow_dispatch") + ("updated_at" to "2026-10-30T11:00:00Z")
        val s = Script(listOf(task(30)), listOf(pullJson(12, body = closes(30), sha = shaD)), mapOf(shaD to runsJson(dispatched)), mapOf(shaD to 0L))
        assertEquals(MergeLane.NEXT, s.lanes()[30])
    }

    @Test fun noRepoNoPrAndCompareFailure() {
        val s = Script(
            listOf(task(30, body = "no repo here"), task(29), task(28)),
            listOf(pullJson(12, body = closes(30)), pullJson(14, body = closes(28))),
            mapOf(SHA_A to runsJson(passed())),
            behind = emptyMap(), // compare answers 500
        )
        val lanes = s.lanes()
        assertEquals(MergeWait.NO_PR, lanes[30]?.wait)
        assertEquals(MergeWait.NO_PR, lanes[29]?.wait)
        assertEquals(MergeWait.UNKNOWN, lanes[28]?.wait)
        assertTrue(lanes.values.none { it?.isNext == true })
    }

    @Test fun noAccessToTheRepoIsUnknownButTheBoardStillLoads() {
        val http = FakeHttp()
            .on({ it.url.startsWith("$boardUrl/issues?") && "state=open" in it.url }) { ok(page(task(30))) }
            .on({ it.url.startsWith("$boardUrl/issues?") }) { ok("[]") }
            .on({ true }) { status(404) }
        val state = BoardLoader(FIXED_CLOCK, mergeLanes = MergeLaneLoader()).refresh(WidgetState.INITIAL, GitHubApi(http, TOKEN, FIXED_CLOCK))
        assertNull(state.error)
        assertEquals(MergeWait.UNKNOWN, state.board!!.section(Section.NEEDS_YOU).items.single().mergeLane?.wait)
    }

    @Test fun outOfTimeEveryRowIsUnknown() {
        val s = Script(listOf(task(30), task(29)), emptyList())
        val never = TimeLimit { /* never starts: the wait always times out */ }
        val lanes = s.lanes(MergeLaneLoader(Duration.ofMillis(1), never))
        assertEquals(mapOf(30 to MergeLane(false, MergeWait.UNKNOWN, null), 29 to MergeLane(false, MergeWait.UNKNOWN, null)), lanes)
    }

    @Test fun boardWithoutMergeRowsMakesNoPrRequests() {
        val s = Script(listOf(task(30, labels = arrayOf("status: todo"))), emptyList())
        s.refresh()
        assertEquals(0, s.count("/Taskboard-widget/"))
    }

    @Test fun lanesSurviveSavingAndLoading() {
        val s = Script(
            listOf(task(30), task(29)),
            listOf(pullJson(12, body = closes(30)), pullJson(13, body = closes(29), draft = true)),
            mapOf(SHA_A to runsJson(passed())), mapOf(SHA_A to 0L),
        )
        val state = s.refresh()
        assertEquals(state, WidgetStateCodec.decode(WidgetStateCodec.encode(state)))
        val lanes = state.board!!.section(Section.NEEDS_YOU).items.associate { it.number to it.mergeLane }
        assertEquals(MergeLane.NEXT, lanes[30])
        assertEquals(MergeLane(false, MergeWait.DRAFT, "Task 30"), lanes[29])
    }

    @Test fun anUnknownStoredReasonDropsOnlyTheLane() {
        val s = Script(listOf(task(29)), listOf(pullJson(13, body = closes(29), draft = true)))
        val text = WidgetStateCodec.encode(s.refresh()).replace("\"DRAFT\"", "\"SOMETHING_NEW\"")
        val item = WidgetStateCodec.decode(text).board!!.section(Section.NEEDS_YOU).items.single()
        assertTrue(item.canMerge)
        assertNull(item.mergeLane)
    }

    // Reviewer edge cases (#45).

    @Test fun allDraftOrStackedNothingNextAndNoPerPrRequests() {
        val s = Script(
            listOf(task(30), task(29)),
            listOf(pullJson(12, body = closes(30), draft = true), pullJson(13, body = closes(29), base = "claude/12-branch")),
        )
        assertEquals(mapOf(30 to MergeLane(false, MergeWait.DRAFT, null), 29 to MergeLane(false, MergeWait.STACKED, null)), s.lanes())
        assertEquals(0, s.count("/pulls/1") + s.count("/actions/runs") + s.count("/compare/"))
    }

    @Test fun needsRebaseLabelBeatsOtherwiseReady() {
        val labelled = pullJson(12, body = closes(30), sha = SHA_A) + ("labels" to listOf(mapOf("name" to "needs-rebase")))
        val s = Script(
            listOf(task(30), task(29)),
            listOf(labelled, pullJson(13, body = closes(29), sha = SHA_B)),
            mapOf(SHA_A to runsJson(doneRun(SHA_A, "2026-10-30T09:00:00Z")), SHA_B to runsJson(doneRun(SHA_B, "2026-10-30T10:00:00Z"))),
            mapOf(SHA_A to 0L, SHA_B to 0L),
        )
        val lanes = s.lanes()
        assertEquals(MergeLane.NEXT, lanes[29])
        assertEquals(MergeLane(false, MergeWait.CONFLICT_CLAUDE_FIXES, "Task 29"), lanes[30])
    }

    @Test fun runsApiForbiddenIsUnknownNeverNext() {
        val s = Script(listOf(task(30)), listOf(pullJson(12, body = closes(30))), behind = mapOf(SHA_A to 0L), runsStatus = 403)
        assertEquals(MergeLane(false, MergeWait.UNKNOWN, null), s.lanes()[30])
        assertEquals("no compare without passed checks", 0, s.count("/compare/"))
    }

    @Test fun aRunWithoutAnyTimeIsStillReadyButGoesLast() {
        val noTime = wfRun(sha = shaC, createdAt = null)
        val s = Script(
            listOf(task(30), task(29)),
            listOf(pullJson(12, body = closes(30), sha = shaC), pullJson(13, body = closes(29), sha = SHA_B)),
            mapOf(shaC to runsJson(noTime), SHA_B to runsJson(doneRun(SHA_B, "2026-10-30T23:59:00Z"))),
            mapOf(shaC to 0L, SHA_B to 0L),
        )
        val lanes = s.lanes()
        assertEquals(MergeLane.NEXT, lanes[29])
        assertEquals(MergeWait.QUEUED, lanes[30]?.wait)
    }

    @Test fun pastTheRefreshDeadlineEveryRowIsUnknownWithoutRequests() {
        val s = Script(listOf(task(30)), listOf(pullJson(12, body = closes(30))), mapOf(SHA_A to runsJson(passed())), mapOf(SHA_A to 0L))
        val late = MergeLaneLoader(clock = FIXED_CLOCK, deadline = FIXED_CLOCK.instant())
        assertEquals(MergeLane(false, MergeWait.UNKNOWN, null), s.lanes(late)[30])
        assertEquals(0, s.count("/Taskboard-widget/"))
    }

    @Test fun forRefreshStillRanksWithinTheRefreshLimit() {
        val s = Script(listOf(task(30)), listOf(pullJson(12, body = closes(30))), mapOf(SHA_A to runsJson(passed())), mapOf(SHA_A to 0L))
        val loader = MergeLaneLoader.forRefresh(java.time.Clock.systemUTC())
        assertEquals(MergeLane.NEXT, s.lanes(loader)[30])
    }

    @Test fun aBoardSavedBeforeMergeNextDecodesWithoutLanes() {
        val s = Script(listOf(task(30)), listOf(pullJson(12, body = closes(30))))
        val withoutLanes = BoardLoader(FIXED_CLOCK).refresh(WidgetState.INITIAL, GitHubApi(s.http, TOKEN, FIXED_CLOCK))
        val old = WidgetStateCodec.encode(withoutLanes).replace(Regex(",?\\s*\"lane\"\\s*:\\s*null"), "")
        assertTrue("fixture really is lane-free", "\"lane\"" !in old)
        val item = WidgetStateCodec.decode(old).board!!.section(Section.NEEDS_YOU).items.single()
        assertTrue(item.canMerge)
        assertNull("rendered as the plain Merge of #30", item.mergeLane)
    }
}
