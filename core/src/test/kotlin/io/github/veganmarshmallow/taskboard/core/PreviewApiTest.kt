package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.github.MalformedResponseException
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.json.JsonWriter
import io.github.veganmarshmallow.taskboard.core.preview.ClaudeComments
import io.github.veganmarshmallow.taskboard.core.preview.IssueComment
import io.github.veganmarshmallow.taskboard.core.preview.IssueDetails
import io.github.veganmarshmallow.taskboard.core.preview.PreviewApi
import io.github.veganmarshmallow.taskboard.core.preview.PreviewJsonParser
import io.github.veganmarshmallow.taskboard.core.preview.PreviewLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

class PreviewApiTest {
    private val base = "https://api.github.com/repos/VeganMarshmallow/claude-workspace/issues"

    private fun preview(http: FakeHttp) = PreviewApi(GitHubApi(http, TOKEN, FIXED_CLOCK))

    private fun issueBody(n: Int, body: Any?, vararg labels: String) =
        JsonWriter.write(issueJson(n, *labels) + ("body" to body))

    private fun commentsJson(vararg bodies: String) = JsonWriter.write(
        bodies.mapIndexed { i, b -> mapOf("id" to (i + 1).toLong(), "body" to b, "created_at" to "2026-10-2${i % 10}T10:00:00Z", "user" to mapOf("login" to "x")) },
    )

    private fun Outcome<*>.error() = (this as Outcome.Failure).error

    // --- issue ---

    @Test fun fetchesOneIssueWithItsBody() {
        val http = FakeHttp().on({ it.url == "$base/11" }) { ok(issueBody(11, "### What should be done\nX", "status: todo")) }
        val details = (preview(http).fetchIssue(11) as Outcome.Success).value
        assertEquals(11, details.issue.number)
        assertEquals(setOf("status: todo"), details.issue.labels)
        assertEquals("### What should be done\nX", details.body)
        assertEquals("GET", http.requests.single().method)
        assertEquals("Bearer $TOKEN", http.requests.single().headers["Authorization"])
    }

    @Test fun nullOrMissingOrNonStringBodyIsEmpty() {
        listOf(null, 5L, listOf("x")).forEach { body ->
            val http = FakeHttp().on({ true }) { ok(issueBody(3, body)) }
            assertEquals("", (preview(http).fetchIssue(3) as Outcome.Success).value.body)
        }
    }

    @Test fun issueGoneStatuses() {
        listOf(301, 404, 410).forEach { code ->
            val http = FakeHttp().on({ true }) { status(code) }
            assertEquals(BoardError.IssueGone(5), preview(http).fetchIssue(5).error())
        }
    }

    @Test fun otherHttpErrorsClassifyAsUsual() {
        assertEquals(BoardError.BadToken(401), preview(FakeHttp().on({ true }) { status(401) }).fetchIssue(5).error())
        assertEquals(BoardError.Server(502), preview(FakeHttp().on({ true }) { status(502) }).fetchIssue(5).error())
        val limited = preview(FakeHttp().on({ true }) { status(403, "x-ratelimit-remaining" to "0") }).fetchIssue(5).error()
        assertTrue(limited is BoardError.RateLimited)
    }

    @Test fun networkFailureAndTimeout() {
        val http = FakeHttp().on({ true }) { throw IOException("timeout") }
        assertEquals(BoardError.Network, preview(http).fetchIssue(5).error())
    }

    @Test fun malformedIssueJson() {
        listOf("", "not json", "[]", "{}", "{\"number\":\"x\",\"state\":\"open\"}", "null").forEach { body ->
            val err = preview(FakeHttp().on({ true }) { ok(body) }).fetchIssue(5).error()
            assertTrue("$body -> $err", err is BoardError.Unexpected)
        }
    }

    @Test fun mismatchedIssueNumberIsRejected() {
        val http = FakeHttp().on({ true }) { ok(issueBody(99, "x")) }
        assertEquals(BoardError.Unexpected("issue number mismatch"), preview(http).fetchIssue(5).error())
    }

    @Test fun invalidIssueNumberIsRefusedBeforeAnyRequest() {
        val http = FakeHttp()
        listOf(0, -1).forEach { n -> assertThrows(IllegalArgumentException::class.java) { preview(http).fetchIssue(n) } }
        assertTrue(http.requests.isEmpty())
    }

    @Test fun bomAndHebrewBody() {
        val http = FakeHttp().on({ true }) { ok("\uFEFF" + issueBody(4, "שלום")) }
        assertEquals("שלום", (preview(http).fetchIssue(4) as Outcome.Success).value.body)
    }

    // --- comments ---

    @Test fun fetchesCommentsFirstPage() {
        val http = FakeHttp().on({ it.url == "$base/7/comments?per_page=100" }) { ok(commentsJson("a", "🤖 b")) }
        val comments = (preview(http).fetchRecentComments(7) as Outcome.Success).value
        assertEquals(listOf("a", "🤖 b"), comments.map { it.body })
        assertEquals(Instant.parse("2026-10-21T10:00:00Z"), comments[1].createdAt)
    }

    @Test fun manyPagesFetchesFirstAndLastOnly() {
        val last = "https://api.github.com/repositories/1/issues/7/comments?per_page=100&page=5"
        val http = FakeHttp()
            .on({ it.url == last }) { ok(commentsJson("✅ newest")) }
            .on({ "comments" in it.url }) {
                ok(commentsJson("old"), "link" to "<https://api.github.com/repositories/1/issues/7/comments?page=2>; rel=\"next\", <$last>; rel=\"last\"")
            }
        val comments = (preview(http).fetchRecentComments(7) as Outcome.Success).value
        assertEquals(listOf("old", "✅ newest"), comments.map { it.body })
        assertEquals(2, http.requests.size)
    }

    @Test fun lastLinkWithoutNextIsIgnored() {
        val http = FakeHttp().on({ true }) { ok(commentsJson("only"), "link" to "<https://api.github.com/x?page=1>; rel=\"last\"") }
        assertEquals(1, (preview(http).fetchRecentComments(7) as Outcome.Success).value.size)
        assertEquals(1, http.requests.size)
    }

    @Test fun foreignLastLinkIsNeverFollowed() {
        val http = FakeHttp().on({ true }) {
            ok(commentsJson("x"), "link" to "<https://api.github.com/a?page=2>; rel=\"next\", <https://evil.example/steal>; rel=\"last\"")
        }
        assertEquals(BoardError.Unexpected("bad pagination link"), preview(http).fetchRecentComments(7).error())
        assertTrue(http.requests.none { "evil" in it.url })
    }

    @Test fun nextWithoutLastUsesTheFirstPageOnly() {
        val http = FakeHttp().on({ true }) { ok(commentsJson("a"), "link" to "<https://api.github.com/x?page=2>; rel=\"next\"") }
        assertEquals(listOf("a"), (preview(http).fetchRecentComments(7) as Outcome.Success).value.map { it.body })
        assertEquals(1, http.requests.size)
    }

    @Test fun twoPagesWhereNextIsLast() {
        val second = "https://api.github.com/repositories/1/issues/7/comments?per_page=100&page=2"
        val http = FakeHttp()
            .on({ it.url == second }) { ok(commentsJson("⛔ newest")) }
            .on({ true }) { ok(commentsJson("🤖 old"), "link" to "<$second>; rel=\"next\", <$second>; rel=\"last\"") }
        val comments = (preview(http).fetchRecentComments(7) as Outcome.Success).value
        assertEquals("⛔ newest", ClaudeComments.latestMessage(comments)?.body)
        assertEquals(2, http.requests.size)
    }

    @Test fun relationsInAnyOrderAndCase() {
        val last = "https://api.github.com/repositories/1/issues/7/comments?page=9"
        val http = FakeHttp()
            .on({ it.url == last }) { ok(commentsJson("✅ z")) }
            .on({ true }) { ok(commentsJson("a"), "Link" to "<$last>; REL=\"last\", <https://api.github.com/p?page=2>; rel=next") }
        assertEquals(listOf("a", "✅ z"), (preview(http).fetchRecentComments(7) as Outcome.Success).value.map { it.body })
    }

    @Test fun emptyLastPageOrEmptyFirstPage() {
        val last = "https://api.github.com/repositories/1/issues/7/comments?page=3"
        val http = FakeHttp()
            .on({ it.url == last }) { ok("[]") }
            .on({ true }) { ok(commentsJson("🤖 a"), "link" to "<https://api.github.com/p?page=2>; rel=\"next\", <$last>; rel=\"last\"") }
        assertEquals(listOf("🤖 a"), (preview(http).fetchRecentComments(7) as Outcome.Success).value.map { it.body })
        val empty = FakeHttp().on({ true }) { ok("[]") }
        assertEquals(emptyList<IssueComment>(), (preview(empty).fetchRecentComments(7) as Outcome.Success).value)
    }

    @Test fun failedOrMalformedLastPageFailsTheComments() {
        // The first page alone could show an outdated "latest" Claude line, so a broken last page shows none.
        val last = "https://api.github.com/repositories/1/issues/7/comments?page=3"
        fun withLast(response: () -> io.github.veganmarshmallow.taskboard.core.http.HttpResponse) = FakeHttp()
            .on({ it.url == last }) { response() }
            .on({ true }) { ok(commentsJson("🤖 old"), "link" to "<https://api.github.com/p?page=2>; rel=\"next\", <$last>; rel=\"last\"") }
        assertEquals(BoardError.Server(503), preview(withLast { status(503) }).fetchRecentComments(7).error())
        assertTrue(preview(withLast { ok("{\"not\":\"a list\"}") }).fetchRecentComments(7).error() is BoardError.Unexpected)
        assertTrue(preview(withLast { throw IOException("reset") }).fetchRecentComments(7).error() == BoardError.Network)
    }

    @Test fun expiredTokenOrRateLimitOnComments() {
        assertEquals(BoardError.BadToken(401), preview(FakeHttp().on({ true }) { status(401) }).fetchRecentComments(7).error())
        assertTrue(preview(FakeHttp().on({ true }) { status(429) }).fetchRecentComments(7).error() is BoardError.RateLimited)
    }

    @Test fun transferredIssueRedirectIsNeverFollowed() {
        val http = FakeHttp().on({ true }) { status(301, "location" to "https://api.github.com/repositories/9/issues/1") }
        assertEquals(BoardError.IssueGone(5), preview(http).fetchIssue(5).error())
        assertEquals(1, http.requests.size)
    }

    @Test fun approvingAnAlreadyApprovedIssueSucceeds() {
        // GitHub answers 200 with the label list when the label is already there.
        val http = FakeHttp().on({ it.method == "POST" }) { ok("[{\"name\":\"ready-for-claude\"}]") }
        assertTrue(GitHubApi(http, TOKEN, FIXED_CLOCK).approve(5) is Outcome.Success)
        val gone = FakeHttp().on({ true }) { status(410) }
        assertEquals(BoardError.IssueGone(5), GitHubApi(gone, TOKEN, FIXED_CLOCK).approve(5).error())
    }

    @Test fun loaderWithExpiredTokenReportsBadTokenAndSkipsComments() {
        val http = FakeHttp().on({ true }) { status(401) }
        val r = load(GitHubApi(http, TOKEN, FIXED_CLOCK))
        assertEquals(BoardError.BadToken(401), r.issues.single().error())
        assertTrue(r.comments.isEmpty())
    }

    @Test fun loaderWithInvalidNumberReportsAnErrorInsteadOfThrowing() {
        listOf(0, -5, Int.MIN_VALUE).forEach { n ->
            val http = FakeHttp()
            val r = load(GitHubApi(http, TOKEN, FIXED_CLOCK), n)
            assertTrue(r.issues.single().error() is BoardError.Internal) // rejected in the app, GitHub never asked (#55)
            assertTrue(http.requests.isEmpty())
        }
    }

    @Test fun commentsFailures() {
        assertEquals(BoardError.Network, preview(FakeHttp()).fetchRecentComments(7).error()) // no route -> IOException
        assertEquals(BoardError.Server(500), preview(FakeHttp().on({ true }) { status(500) }).fetchRecentComments(7).error())
        assertTrue(preview(FakeHttp().on({ true }) { ok("{}") }).fetchRecentComments(7).error() is BoardError.Unexpected)
    }

    @Test fun commentParsingIsTolerant() {
        val parsed = PreviewJsonParser.parseComments(
            """[{"id":1,"body":"a","created_at":"bad"},{"id":2,"body":null},{"body":"no id","created_at":"2026-10-22T10:00:00+03:00"},{"id":3}]""",
        )
        assertEquals(listOf(IssueComment(1, "a", null), IssueComment(0, "no id", Instant.parse("2026-10-22T07:00:00Z"))), parsed)
        assertEquals(emptyList<IssueComment>(), PreviewJsonParser.parseComments("[]"))
        assertThrows(MalformedResponseException::class.java) { PreviewJsonParser.parseComments("[1]") }
        assertThrows(MalformedResponseException::class.java) { PreviewJsonParser.parseComments("{") }
    }

    // --- loader ---

    private class Recorder {
        val issues = mutableListOf<Outcome<IssueDetails>>()
        val comments = mutableListOf<List<IssueComment>>()
    }

    private fun load(api: GitHubApi?, n: Int = 7) = Recorder().also { r ->
        PreviewLoader(api).load(n, { r.issues += it }, { r.comments += it })
    }

    @Test fun loaderDeliversIssueThenComments() {
        val http = FakeHttp()
            .on({ "comments" in it.url }) { ok(commentsJson("🤖 hi")) }
            .on({ true }) { ok(issueBody(7, "x")) }
        val r = load(GitHubApi(http, TOKEN, FIXED_CLOCK))
        assertTrue(r.issues.single() is Outcome.Success)
        assertEquals("🤖 hi", r.comments.single().single().body)
    }

    @Test fun loaderWithoutTokenReportsNoToken() {
        val r = load(null)
        assertEquals(BoardError.NoToken, r.issues.single().error())
        assertTrue(r.comments.isEmpty())
    }

    @Test fun loaderSkipsCommentsWhenIssueFails() {
        val http = FakeHttp().on({ true }) { status(404) }
        val r = load(GitHubApi(http, TOKEN, FIXED_CLOCK))
        assertEquals(BoardError.IssueGone(7), r.issues.single().error())
        assertTrue(r.comments.isEmpty())
        assertEquals(1, http.requests.size)
    }

    @Test fun loaderSwallowsCommentFailures() {
        val http = FakeHttp()
            .on({ "comments" in it.url }) { status(500) }
            .on({ true }) { ok(issueBody(7, "x")) }
        val r = load(GitHubApi(http, TOKEN, FIXED_CLOCK))
        assertTrue(r.issues.single() is Outcome.Success)
        assertTrue(r.comments.isEmpty())
    }

    @Test fun loaderTurnsUnexpectedExceptionsIntoErrors() {
        val http = FakeHttp().on({ true }) { throw IllegalStateException("boom") }
        val r = load(GitHubApi(http, TOKEN, FIXED_CLOCK))
        assertTrue(r.issues.single().error() is BoardError.Unexpected)
        val bad = HttpResponse(200, body = "x")
        assertNull(bad.header("link"))
    }
}
