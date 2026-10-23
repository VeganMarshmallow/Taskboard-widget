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
        var checks: String = checkRunsJson(passed("build"), passed("emulator"))
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
                url.contains("/check-runs") -> ok(checks)
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
        s.override({ _, u -> u == "$site/pulls/3" }) { ok(JsonWriter.write(pullJson(3, body = "no closes line"))) }
        s.override({ _, u -> u.startsWith("$site/commits/") }) { ok(checkRunsJson(passed())) }
        val pr = s.prepare().pr()
        assertEquals("VeganMarshmallow/site", pr.repo.fullName)
        assertEquals(3, pr.number)
    }

    @Test fun shortFormCommentUsesTheRepoField() {
        val s = Script()
        s.open = listOf(pullJson(12, body = "unrelated"))
        s.comments = listOf("✅ Taskboard-widget PR #12 is ready for review")
        assertEquals(12, s.prepare().pr().number)
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
        assertTrue("never merges or checks when ambiguous", s.urls().none { "check-runs" in it || it.startsWith("PUT") })
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
        val pr = s.prepare().pr()
        assertEquals("merges the SHA that was checked", SHA_B, pr.headSha)
        assertEquals(listOf(Duration.ofMillis(1500), Duration.ofMillis(3000)), sleeps)
        assertTrue(s.urls().any { it.contains("/commits/$SHA_B/check-runs") })
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
        assertTrue(s.urls().none { "check-runs" in it })
    }

    @Test fun prClosedWhileChecking() {
        val s = Script()
        s.detail(12, pullJson(12, state = "closed"))
        assertTrue(s.prepare().problem() is MergeProblem.Closed)
    }

    @Test fun checksMixed() {
        val s = Script()
        s.checks = checkRunsJson(passed("build"), Triple("emulator", "in_progress", null), Triple("lint", "completed", "skipped"))
        assertEquals(listOf("emulator"), (s.prepare().problem() as MergeProblem.ChecksRunning).names)
    }

    @Test fun checksFailed() {
        val s = Script()
        s.checks = checkRunsJson(passed("build"), Triple("emulator", "completed", "failure"))
        assertEquals(listOf("emulator"), (s.prepare().problem() as MergeProblem.ChecksFailed).names)
    }

    @Test fun noChecksOrOnlySkipped() {
        val s = Script()
        s.checks = checkRunsJson()
        assertTrue(s.prepare().problem() is MergeProblem.NoChecks)
        s.checks = checkRunsJson(Triple("deploy", "completed", "skipped"), Triple("lint", "completed", "neutral"))
        assertTrue(s.prepare().problem() is MergeProblem.NoChecks)
    }

    @Test fun neutralAndSkippedPassWithASuccess() {
        val s = Script()
        s.checks = checkRunsJson(passed(), Triple("deploy", "completed", "skipped"), Triple("lint", "completed", "neutral"))
        assertEquals(12, s.prepare().pr().number)
    }

    @Test fun checkRunsArePaginated() {
        val s = Script()
        val next = "$repo/commits/$SHA_A/check-runs?per_page=100&page=2"
        s.override({ _, u -> u == next }) { ok(checkRunsJson(Triple("late", "completed", "failure"))) }
        s.override({ _, u -> u.endsWith("/check-runs?per_page=100") }) {
            ok(checkRunsJson(passed()), "Link" to "<$next>; rel=\"next\"")
        }
        assertEquals(listOf("late"), (s.prepare().problem() as MergeProblem.ChecksFailed).names)
    }

    @Test fun checksNeedingAPermissionTheTokenLacks() {
        val s = Script()
        s.override({ _, u -> "check-runs" in u }) { status(403, body = """{"message":"Resource not accessible by personal access token"}""") }
        assertEquals(MergeProblem.NoRepoAccess(WIDGET), s.prepare().problem())
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
        val t = Script().override({ _, u -> "check-runs" in u }) { throw SocketTimeoutException() }
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
