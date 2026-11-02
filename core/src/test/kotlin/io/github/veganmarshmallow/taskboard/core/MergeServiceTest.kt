package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.json.JsonWriter
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.merge.MergeService
import io.github.veganmarshmallow.taskboard.core.merge.MergeStep
import io.github.veganmarshmallow.taskboard.core.merge.PullRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration

/** The whole merge flow against a scripted GitHub: finding the PR, every pre-merge check, and the merge. */
class MergeServiceTest {
    private val board = "$API_REPOS/VeganMarshmallow/claude-workspace/issues/30"
    private val repo = "$API_REPOS/VeganMarshmallow/Taskboard-widget"
    private val repoBody = "### Repo\n\nVeganMarshmallow/Taskboard-widget\n\n### What should be done\n\nMerge from the widget"
    private val sleeps = mutableListOf<Duration>()

    /** A scripted GitHub; routes added later win, so tests override the defaults. */
    private inner class Script {
        val http = FakeHttp()
        private val overrides = mutableListOf<Pair<(String, String) -> Boolean, () -> HttpResponse>>()
        var issueBody: String = repoBody
        var comments: List<String> = listOf("🤖 Started")
        var open: List<Map<String, Any?>> = listOf(pullJson(12))
        val details = HashMap<Int, ArrayDeque<Map<String, Any?>>>()
        var checks: String = runsJson(passed("CI"), passed("Lint"))
        var mergeResponse: HttpResponse = ok("""{"merged":true}""")

        init {
            http.on({ true }) { request -> route(request.method, request.url) }
        }

        fun override(match: (String, String) -> Boolean, respond: () -> HttpResponse) = apply { overrides.add(0, match to respond) }

        fun detail(number: Int, vararg versions: Map<String, Any?>) = apply { details[number] = ArrayDeque(versions.toList()) }

        private fun route(method: String, url: String): HttpResponse {
            overrides.firstOrNull { it.first(method, url) }?.let { return it.second() }
            return when {
                url == board -> ok(JsonWriter.write(issueJson(30, "status: in-review") + ("body" to issueBody)))
                url.startsWith("$board/comments") -> ok(commentsJson(*comments.toTypedArray()))
                url.startsWith("$repo/pulls?state=open") -> ok(pullsPage(*open.toTypedArray()))
                method == "PUT" && url.matches(Regex("""$repo/pulls/\d+/merge""")) -> mergeResponse
                url.matches(Regex("""$repo/pulls/\d+""")) -> {
                    val n = url.substringAfterLast('/').toInt()
                    val queue = details[n]
                    val json = when {
                        queue == null -> open.firstOrNull { it["number"] == n.toLong() } ?: return status(404)
                        queue.size > 1 -> queue.removeFirst()
                        else -> queue.first()
                    }
                    ok(JsonWriter.write(json))
                }
                url.contains("/actions/runs?") -> ok(checks)
                else -> throw IOException("no route for $method $url")
            }
        }

        fun service() = MergeService(GitHubApi(http, TOKEN, FIXED_CLOCK), sleep = { sleeps += it })
        fun prepare() = service().prepare(30)
        fun urls() = http.requests.map { "${it.method} ${it.url.removePrefix(API_REPOS)}" }
    }

    private fun MergeStep<PullRequest>.pr() = (this as MergeStep.Ok).value
    private fun MergeStep<PullRequest>.problem() = (this as MergeStep.Stop).problem

    // --- finding the PR ---

    @Test fun happyPathFindsChecksAndMerges() {
        val s = Script()
        val pr = s.prepare().pr()
        assertEquals(12, pr.number)
        assertEquals(SHA_A, pr.headSha)
        val merged = s.service().merge(pr)
        assertEquals(12, merged.pr().number)
        val put = s.http.requests.last()
        assertEquals("PUT", put.method)
        assertEquals("$repo/pulls/12/merge", put.url)
        assertEquals(mapOf("merge_method" to "merge", "sha" to SHA_A), JsonParser.parse(put.body!!))
        assertEquals("Bearer $TOKEN", put.headers["Authorization"])
    }

    @Test fun hebrewCommentLinkFindsAPrInAnotherRepo() {
        val s = Script()
        s.issueBody = "### Repo\n\n_No response_"
        s.comments = listOf("🤖 התחלתי", "✅ ה-PR מוכן: https://github.com/VeganMarshmallow/site/pull/3 — לבדיקה")
        val site = "$API_REPOS/VeganMarshmallow/site"
        s.override({ _, u -> u == "$site/pulls/3" }) { ok(JsonWriter.write(pullJson(3))) }
        s.override({ _, u -> u.startsWith("$site/actions/runs?") }) { ok(runsJson(passed())) }
        val pr = s.prepare().pr()
        assertEquals("VeganMarshmallow/site", pr.repo.fullName)
        assertEquals(3, pr.number)
    }

    @Test fun aLinkedPrInAnotherRepoThatDoesNotCloseTheTaskIsNeverMerged() {
        val s = Script()
        s.issueBody = "### Repo\n\n_No response_"
        s.comments = listOf("FYI this depends on https://github.com/VeganMarshmallow/site/pull/3")
        val site = "$API_REPOS/VeganMarshmallow/site"
        s.override({ _, u -> u == "$site/pulls/3" }) { ok(JsonWriter.write(pullJson(3, body = "Unrelated work"))) }
        val p = s.prepare().problem() as MergeProblem.NotLinked
        assertEquals(listOf(3), p.candidates.map { it.number })
        assertTrue("never checked or merged", s.urls().none { "actions/runs" in it || it.startsWith("PUT") })
    }

    @Test fun aLinkedPrWhoseBodyOnlyMentionsTheTaskIsNeverMerged() {
        val s = Script()
        s.open = listOf(pullJson(12, body = "Part of VeganMarshmallow/claude-workspace#30, doesn't close it"))
        s.comments = listOf("✅ Taskboard-widget PR #12 is ready for review")
        assertTrue(s.prepare().problem() is MergeProblem.NotLinked)
        assertTrue(s.urls().none { "actions/runs" in it })
    }

    @Test fun shortFormCommentUsesTheRepoField() {
        val s = Script()
        s.open = emptyList()
        s.detail(12, pullJson(12))
        s.comments = listOf("✅ Taskboard-widget PR #12 is ready for review")
        assertEquals(12, s.prepare().pr().number)
    }

    @Test fun aMidSentenceCloseIsNotAClaim() {
        val s = Script()
        s.open = listOf(pullJson(12, body = "Note: this doesn't close VeganMarshmallow/claude-workspace#30"))
        assertTrue(s.prepare().problem() is MergeProblem.NotLinked)
    }

    @Test fun exactBodyMatchIsPreferredOverALinkedPr() {
        val s = Script()
        s.comments = listOf("Looks like https://github.com/VeganMarshmallow/Taskboard-widget/pull/9 is related")
        s.open = listOf(pullJson(9, body = "something else"), pullJson(12))
        assertEquals(12, s.prepare().pr().number)
    }

    @Test fun issueNumberPrefixDoesNotMatch() {
        val s = Script()
        s.open = listOf(pullJson(12, body = "Closes VeganMarshmallow/claude-workspace#300"))
        assertEquals(MergeProblem.NotFound, s.prepare().problem())
    }

    @Test fun multiplePrsPickTheOneOnTheDefaultBranch() {
        val s = Script()
        s.open = listOf(pullJson(12), pullJson(13, base = "claude/12-branch"))
        assertEquals(12, s.prepare().pr().number)
    }

    @Test fun multiplePrsOnTheDefaultBranchAreAmbiguous() {
        val s = Script()
        s.open = listOf(pullJson(12), pullJson(14))
        val p = s.prepare().problem() as MergeProblem.Ambiguous
        assertEquals(listOf(12, 14), p.candidates.map { it.number })
        assertTrue("never merges or checks when ambiguous", s.urls().none { "actions/runs" in it || it.startsWith("PUT") })
    }

    @Test fun noRepoFieldAndNoLinksIsNotFound() {
        val s = Script()
        s.issueBody = "Just a description"
        assertEquals(MergeProblem.NotFound, s.prepare().problem())
    }

    @Test fun alreadyMergedLinkedPr() {
        val s = Script()
        s.open = emptyList()
        s.comments = listOf("PR https://github.com/VeganMarshmallow/Taskboard-widget/pull/12")
        s.detail(12, pullJson(12, state = "closed", merged = true))
        assertTrue(s.prepare().problem() is MergeProblem.AlreadyMerged)
    }

    @Test fun aBrokenCommentLinkDoesNotHideThePrInTheRepo() {
        val s = Script()
        s.comments = listOf("PR https://github.com/VeganMarshmallow/Taskboard-widget/pull/999")
        assertEquals(12, s.prepare().pr().number) // 999 answers 404
    }

    @Test fun noAccessToTheProjectRepo() {
        val s = Script()
        s.override({ _, u -> u.startsWith(repo) }) { status(404, body = """{"message":"Not Found"}""") }
        assertEquals(MergeProblem.NoRepoAccess(WIDGET), s.prepare().problem())
    }

    // --- pre-merge checks ---

    @Test fun draftIsRefused() {
        val s = Script()
        s.open = listOf(pullJson(12, draft = true))
        assertTrue(s.prepare().problem() is MergeProblem.Draft)
    }

    @Test fun stackedPrNamesThePrToMergeFirst() {
        val s = Script()
        s.open = listOf(pullJson(3, body = "Closes VeganMarshmallow/claude-workspace#29", head = "claude/29-first"),
            pullJson(4, base = "claude/29-first"))
        val p = s.prepare().problem() as MergeProblem.Stacked
        assertEquals(4, p.pr.number)
        assertEquals(3, p.blocker)
        assertEquals("claude/29-first", p.pr.baseRef)
        assertTrue("no mergeability polling for a stacked PR", s.urls().none { it.endsWith("/pulls/4") })
    }

    @Test fun stackedOnABranchWithoutAnOpenPr() {
        val s = Script()
        s.open = listOf(pullJson(4, base = "feature"))
        assertEquals(null, (s.prepare().problem() as MergeProblem.Stacked).blocker)
    }

    @Test fun missingDefaultBranchIsFetchedFromTheRepo() {
        val s = Script()
        s.open = listOf(pullJson(4, base = "develop", defaultBranch = null))
        s.override({ _, u -> u == repo }) { ok("""{"default_branch":"develop"}""") }
        assertEquals(4, s.prepare().pr().number)
        assertEquals("the repo is asked once", 1, s.urls().count { it == "GET /VeganMarshmallow/Taskboard-widget" })
    }

    @Test fun mergeableNullIsRepolledUntilKnown() {
        val s = Script()
        s.detail(12, pullJson(12, mergeable = null), pullJson(12, mergeable = null), pullJson(12, mergeable = true, sha = SHA_B))
        s.checks = runsJson(wfRun("CI", sha = SHA_B))
        val pr = s.prepare().pr()
        assertEquals("merges the SHA that was checked", SHA_B, pr.headSha)
        assertEquals(listOf(Duration.ofMillis(1500), Duration.ofMillis(3000)), sleeps)
        assertTrue(s.urls().any { it.contains("/actions/runs?head_sha=$SHA_B&") })
    }

    @Test fun mergeableStillNullGivesUp() {
        val s = Script()
        s.detail(12, pullJson(12, mergeable = null))
        assertTrue(s.prepare().problem() is MergeProblem.MergeabilityUnknown)
        assertEquals(3, s.urls().count { it.endsWith("/pulls/12") })
    }

    @Test fun mergeableFalseIsConflicts() {
        val s = Script()
        s.detail(12, pullJson(12, mergeable = false))
        assertTrue(s.prepare().problem() is MergeProblem.Conflicts)
        assertTrue(s.urls().none { "actions/runs" in it })
    }

    @Test fun prClosedWhileChecking() {
        val s = Script()
        s.detail(12, pullJson(12, state = "closed"))
        assertTrue(s.prepare().problem() is MergeProblem.Closed)
    }

    @Test fun happyPathReadsActionsRunsOnTheHeadSha() {
        val s = Script()
        assertEquals(12, s.prepare().pr().number)
        assertTrue(s.urls().contains("GET /VeganMarshmallow/Taskboard-widget/actions/runs?head_sha=$SHA_A&per_page=100"))
        assertTrue("check runs need a permission fine-grained tokens lack", s.urls().none { "check-runs" in it || "/status" in it })
    }

    @Test fun checksMixed() {
        val s = Script()
        s.checks = runsJson(passed("CI"), wfRun("Emulator", null, "in_progress"), wfRun("Deploy", "skipped"))
        assertEquals(listOf("Emulator"), (s.prepare().problem() as MergeProblem.ChecksRunning).names)
        s.checks = runsJson(passed("CI"), wfRun("Approval", null, "waiting"), wfRun("Queued", null, "queued"))
        assertEquals(listOf("Approval", "Queued"), (s.prepare().problem() as MergeProblem.ChecksRunning).names)
    }

    @Test fun aWorkflowThatRanForThePrButNotYetOnTheHeadIsStillRunning() {
        val s = Script()
        s.override({ _, u -> "/actions/runs?branch=" in u }) {
            ok(runsJson(wfRun("Fast", workflow = 1, sha = SHA_B, event = "pull_request"), wfRun("Slow", workflow = 2, sha = SHA_B, event = "pull_request")))
        }
        s.override({ _, u -> "/actions/workflows" in u }) {
            ok("""{"total_count":2,"workflows":[{"id":1,"name":"Fast","state":"active"},{"id":2,"name":"Slow","state":"active"}]}""")
        }
        s.checks = runsJson(wfRun("Fast", workflow = 1, event = "pull_request"))
        assertEquals(listOf("Slow (not started)"), (s.prepare().problem() as MergeProblem.ChecksRunning).names)
        assertTrue("never merges", s.urls().none { it.startsWith("PUT") })
        s.checks = runsJson(wfRun("Fast", workflow = 1, event = "pull_request"), wfRun("Slow", workflow = 2, event = "pull_request"))
        assertEquals(12, s.prepare().pr().number)
        val before = s.urls().count { "branch=" in it }
        s.checks = runsJson(wfRun("Fast", "failure", workflow = 1, event = "pull_request"))
        assertTrue(s.prepare().problem() is MergeProblem.ChecksFailed)
        assertEquals("a head that fails anyway costs no extra call", before, s.urls().count { "branch=" in it })
    }

    @Test fun checksFailed() {
        val s = Script()
        listOf("failure", "cancelled", "timed_out", "action_required").forEach { c ->
            s.checks = runsJson(passed("CI"), wfRun("Emulator", c))
            assertEquals(c, listOf("Emulator"), (s.prepare().problem() as MergeProblem.ChecksFailed).names)
        }
    }

    @Test fun aFailedRunReRunGreenMerges() {
        val s = Script()
        s.checks = runsJson(wfRun("CI", "failure", attempt = 1), passed("Lint"), wfRun("CI", "success", attempt = 2))
        assertEquals(12, s.prepare().pr().number)
        s.checks = runsJson(wfRun("CI", "failure", number = 4), wfRun("CI", "success", number = 5))
        assertEquals("a newer run of the workflow supersedes the failed one", 12, s.prepare().pr().number)
        s.checks = runsJson(wfRun("CI", "success", attempt = 1), wfRun("CI", null, "in_progress", attempt = 2))
        assertTrue("a re-run in progress is still running", s.prepare().problem() is MergeProblem.ChecksRunning)
    }

    @Test fun aNewerPushRunDoesNotHideAFailedPullRequestRun() {
        val s = Script()
        s.checks = runsJson(wfRun("CI", "failure", number = 4, event = "pull_request"), wfRun("CI", "success", number = 5, event = "push"))
        assertEquals(MergeProblem.ChecksFailed::class, s.prepare().problem()!!::class)
        s.checks = runsJson(wfRun("CI", sha = null), wfRun("Lint"))
        assertTrue("a run without head_sha refuses", s.prepare().problem() is MergeProblem.Failed)
        assertTrue("never merges", s.urls().none { it.startsWith("PUT") })
    }

    @Test fun noChecksOrOnlySkipped() {
        val s = Script()
        s.checks = runsJson()
        assertTrue(s.prepare().problem() is MergeProblem.NoChecks)
        s.checks = runsJson(wfRun("Deploy", "skipped"), wfRun("Docs", "neutral"))
        assertTrue(s.prepare().problem() is MergeProblem.NoChecks)
        s.checks = runsJson(wfRun("CI", sha = SHA_B))
        assertTrue("runs for another commit don't count", s.prepare().problem() is MergeProblem.NoChecks)
    }

    @Test fun neutralAndSkippedPassWithASuccess() {
        val s = Script()
        s.checks = runsJson(passed(), wfRun("Deploy", "skipped"), wfRun("Docs", "neutral"))
        assertEquals(12, s.prepare().pr().number)
    }

    @Test fun workflowRunsArePaginated() {
        val s = Script()
        val first = "$repo/actions/runs?head_sha=$SHA_A&per_page=100"
        val next = "$first&page=2"
        val page1 = (1..100).map { wfRun("W$it", workflow = it.toLong()) }.toTypedArray()
        s.override({ _, u -> u == next }) { ok(runsJson(wfRun("Late", "failure"), total = 101)) }
        s.override({ _, u -> u == first }) { ok(runsJson(*page1, total = 101), "Link" to "<$next>; rel=\"next\", <$next>; rel=\"last\"") }
        assertEquals(listOf("Late"), (s.prepare().problem() as MergeProblem.ChecksFailed).names)
        assertEquals(1, s.urls().count { it.endsWith("&page=2") })
    }

    @Test fun aReRunOnTheNextPageStillSupersedes() {
        val s = Script()
        val first = "$repo/actions/runs?head_sha=$SHA_A&per_page=100"
        val next = "$first&page=2"
        s.override({ _, u -> u == next }) { ok(runsJson(wfRun("CI", "failure", attempt = 1, id = 1), total = 2)) }
        s.override({ _, u -> u == first }) { ok(runsJson(wfRun("CI", attempt = 2, id = 2), total = 2), "Link" to "<$next>; rel=\"next\"") }
        assertEquals(12, s.prepare().pr().number)
    }

    @Test fun aRunSeenOnTwoPagesIsCountedOnce() {
        val s = Script()
        val first = "$repo/actions/runs?head_sha=$SHA_A&per_page=100"
        val next = "$first&page=2"
        val dup = wfRun("CI", id = 7)
        s.override({ _, u -> u == next }) { ok(runsJson(dup, wfRun("Lint", id = 8), total = 2)) }
        s.override({ _, u -> u == first }) { ok(runsJson(dup, total = 2), "Link" to "<$next>; rel=\"next\"") }
        assertEquals(12, s.prepare().pr().number)
    }

    @Test fun missingRunsRefuseInsteadOfJudgingPart() {
        val s = Script()
        val page1 = (1..100).map { passed("W$it") }.toTypedArray()
        s.checks = runsJson(*page1, total = 150) // total_count > 100 but no Link header
        val p = s.prepare().problem() as MergeProblem.Failed
        assertEquals(BoardError.Unexpected("GitHub listed 150 workflow runs but sent 100"), p.error)
        assertTrue(s.urls().none { it.startsWith("PUT") })
    }

    @Test fun paginationOnlyFollowsApiGitHubCom() {
        listOf("https://evil.example/repos/x?page=2", "http://api.github.com/x", "https://api.github.com.evil.example/x").forEach { link ->
            val s = Script()
            s.override({ _, u -> u.contains("/actions/runs?") }) { ok(runsJson(passed(), total = 2), "Link" to "<$link>; rel=\"next\"") }
            val p = s.prepare().problem() as MergeProblem.Failed
            assertEquals(link, BoardError.Unexpected("bad pagination link"), p.error)
            assertTrue(link, s.http.requests.none { it.url == link })
        }
    }

    @Test fun tooManyPagesRefuse() {
        val s = Script()
        var page = 0
        s.override({ _, u -> u.contains("/actions/runs?") }) {
            page++
            ok(runsJson(passed("W$page"), total = 1000), "Link" to "<$repo/actions/runs?head_sha=$SHA_A&per_page=100&page=${page + 1}>; rel=\"next\"")
        }
        val p = s.prepare().problem() as MergeProblem.Failed
        assertEquals(BoardError.Unexpected("more than 300 workflow runs"), p.error)
        assertEquals(3, page)
    }

    @Test fun malformedRunsRefuse() {
        val s = Script()
        s.checks = """{"total_count":1,"check_runs":[]}"""
        assertTrue(s.prepare().problem() is MergeProblem.Failed)
        assertTrue(s.urls().none { it.startsWith("PUT") })
    }

    @Test fun actionsNeedingAPermissionTheTokenLacks() {
        listOf(403, 404).forEach { code ->
            val s = Script()
            s.override({ _, u -> "actions/runs" in u }) { status(code, body = """{"message":"Resource not accessible by personal access token"}""") }
            assertEquals("$code", MergeProblem.NoActionsAccess(WIDGET), s.prepare().problem())
        }
        val limited = Script().override({ _, u -> "actions/runs" in u }) { status(403, "x-ratelimit-remaining" to "0") }
        assertTrue((limited.prepare().problem() as MergeProblem.Failed).error is BoardError.RateLimited)
        val bad = Script().override({ _, u -> "actions/runs" in u }) { status(401) }
        assertEquals(MergeProblem.Failed(BoardError.BadToken(401)), bad.prepare().problem())
        val down = Script().override({ _, u -> "actions/runs" in u }) { status(502) }
        assertTrue((down.prepare().problem() as MergeProblem.Failed).error is BoardError.Server)
    }

    // --- the merge ---

    @Test fun headChangedSinceTheCheckIs409() {
        val s = Script()
        val pr = s.prepare().pr()
        s.mergeResponse = status(409, body = """{"message":"Head branch was modified. Review and try the merge again."}""")
        assertEquals(MergeProblem.HeadChanged(pr), s.service().merge(pr).problem())
    }

    @Test fun conflictsAtMergeTimeAre405() {
        val s = Script()
        val pr = s.prepare().pr()
        s.mergeResponse = status(405, body = """{"message":"Pull Request is not mergeable"}""")
        assertEquals(MergeProblem.Conflicts(pr, "Pull Request is not mergeable"), s.service().merge(pr).problem())
    }

    @Test fun tokenWithoutWriteAccessAtMergeTime() {
        val s = Script()
        val pr = s.prepare().pr()
        s.mergeResponse = status(403, body = """{"message":"Resource not accessible by personal access token"}""")
        assertEquals(MergeProblem.NoRepoAccess(WIDGET, pr), s.service().merge(pr).problem())
    }

    // --- the re-check right before the PUT (after the owner confirmed) ---

    @Test fun mergedElsewhereWhileTheDialogWasOpen() {
        val s = Script()
        val pr = s.prepare().pr()
        s.detail(12, pullJson(12, state = "closed", merged = true))
        assertTrue(s.service().merge(pr).problem() is MergeProblem.AlreadyMerged)
        assertTrue(s.urls().none { it.startsWith("PUT") })
    }

    @Test fun retargetedOntoAnotherBranchWhileTheDialogWasOpen() {
        val s = Script()
        val pr = s.prepare().pr()
        s.detail(12, pullJson(12, base = "claude/11-first"))
        s.open = listOf(pullJson(11, head = "claude/11-first", body = "Closes VeganMarshmallow/claude-workspace#29"), pullJson(12, base = "claude/11-first"))
        assertEquals(11, (s.service().merge(pr).problem() as MergeProblem.Stacked).blocker)
        assertTrue(s.urls().none { it.startsWith("PUT") })
    }

    @Test fun newCommitsOrDraftWhileTheDialogWasOpen() {
        val s = Script()
        val pr = s.prepare().pr()
        s.detail(12, pullJson(12, sha = SHA_B))
        assertEquals(MergeProblem.HeadChanged(pr), s.service().merge(pr).problem())
        s.detail(12, pullJson(12, draft = true))
        assertTrue(s.service().merge(pr).problem() is MergeProblem.Draft)
        s.detail(12, pullJson(12, mergeable = false))
        assertTrue(s.service().merge(pr).problem() is MergeProblem.Conflicts)
        assertTrue(s.urls().none { it.startsWith("PUT") })
    }

    @Test fun mergeableStillComputingAtTheRecheckIsLeftToGitHub() {
        val s = Script()
        val pr = s.prepare().pr()
        s.detail(12, pullJson(12, mergeable = null))
        assertEquals(12, s.service().merge(pr).pr().number)
        val put = s.http.requests.single { it.method == "PUT" }
        assertEquals(mapOf("merge_method" to "merge", "sha" to SHA_A), JsonParser.parse(put.body!!))
    }

    @Test fun aFailedRecheckSendsNoPut() {
        val s = Script()
        val pr = s.prepare().pr()
        s.override({ m, u -> m == "GET" && u == "$repo/pulls/12" }) { throw IOException("down") }
        assertEquals(MergeProblem.Failed(BoardError.Network), s.service().merge(pr).problem())
        assertTrue(s.urls().none { it.startsWith("PUT") })
    }

    @Test fun branchProtectionAtMergeTimeKeepsGitHubsMessage() {
        val s = Script()
        val pr = s.prepare().pr()
        s.mergeResponse = status(405, body = """{"message":"At least 1 approving review is required by reviewers with write access."}""")
        val p = s.service().merge(pr).problem() as MergeProblem.Conflicts
        assertTrue(p.detail!!.startsWith("At least 1 approving review"))
    }

    @Test fun mergeIsNeverRetried() {
        val s = Script()
        val pr = s.prepare().pr()
        s.override({ m, _ -> m == "PUT" }) { throw SocketTimeoutException() }
        assertEquals(MergeProblem.Failed(BoardError.Timeout), s.service().merge(pr).problem())
        assertEquals(1, s.http.requests.count { it.method == "PUT" })
    }

    // --- errors from the board and the network ---

    @Test fun boardErrorsUseTheExistingClassification() {
        val s = Script()
        s.override({ _, u -> u == board }) { status(401) }
        assertEquals(MergeProblem.Failed(BoardError.BadToken(401)), s.prepare().problem())
        val gone = Script().override({ _, u -> u == board }) { status(404) }
        assertEquals(MergeProblem.Failed(BoardError.IssueGone(30)), gone.prepare().problem())
    }

    @Test fun networkAndTimeoutStopAtOnce() {
        val s = Script().override({ _, u -> u.startsWith("$repo/pulls?") }) { throw IOException("down") }
        assertEquals(MergeProblem.Failed(BoardError.Network), s.prepare().problem())
        val t = Script().override({ _, u -> "actions/runs" in u }) { throw SocketTimeoutException() }
        assertEquals(MergeProblem.Failed(BoardError.Timeout), t.prepare().problem())
    }

    @Test fun rateLimitIsNotCalledAnAccessProblem() {
        val s = Script().override({ _, u -> u.startsWith(repo) }) { status(403, "x-ratelimit-remaining" to "0") }
        val p = s.prepare().problem() as MergeProblem.Failed
        assertTrue(p.error is BoardError.RateLimited)
    }

    @Test fun malformedResponsesAreUnexpected() {
        val s = Script().override({ _, u -> u.startsWith("$repo/pulls?") }) { ok("{not json") }
        assertTrue((s.prepare().problem() as MergeProblem.Failed).error is BoardError.Unexpected)
    }

    @Test fun aCrashInsideIsReportedNotThrown() {
        val s = Script().override({ _, u -> u.startsWith("$repo/pulls?") }) { throw IllegalStateException("boom") }
        assertEquals(MergeProblem.Failed(BoardError.Unexpected("request failed: IllegalStateException")), s.prepare().problem())
    }
}
