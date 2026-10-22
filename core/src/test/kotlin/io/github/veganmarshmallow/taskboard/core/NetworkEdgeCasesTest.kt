package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.error.ErrorClassifier
import io.github.veganmarshmallow.taskboard.core.error.ErrorMessages
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.github.LinkHeader
import io.github.veganmarshmallow.taskboard.core.github.PagedFetcher
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import javax.net.ssl.SSLHandshakeException

/** Reviewer-added edge cases for error classification, pagination and request failures. */
class NetworkEdgeCasesTest {
    private val apiBase = "https://api.github.com"
    private fun api(http: FakeHttp, maxPages: Int = 20) = GitHubApi(http, TOKEN, FIXED_CLOCK, maxPages = maxPages)
    private fun Outcome<*>.error() = (this as Outcome.Failure).error
    private fun classify(r: HttpResponse) = ErrorClassifier.classify(r, NOW)

    // --- error classification -------------------------------------------------------------

    @Test fun secondaryRateLimitWithoutHeadersIsDetectedFromTheBody() {
        val body = """{"message":"You have exceeded a secondary rate limit. Please wait a few minutes before you try again."}"""
        assertEquals(BoardError.RateLimited(null), classify(status(403, body = body)))
        assertEquals(BoardError.RateLimited(null), classify(status(403, body = """{"message":"API rate limit exceeded for user ID 1."}""")))
    }

    @Test fun forbiddenForPermissionsIsStillABadToken() {
        val body = """{"message":"Resource not accessible by personal access token","documentation_url":"https://docs.github.com"}"""
        assertEquals(BoardError.BadToken(403), classify(status(403, body = body)))
        assertEquals(BoardError.BadToken(403), classify(status(403, body = "")))
    }

    @Test fun rateLimitMessageOnOtherStatusesDoesNotMatter() {
        assertEquals(BoardError.BadToken(401), classify(status(401, body = "rate limit")))
        assertEquals(BoardError.Server(503), classify(status(503, body = "rate limit")))
    }

    @Test fun resetTimeInThePastFallsBackToRetryAfterOrUnknown() {
        val past = NOW.minusSeconds(3600).epochSecond
        assertEquals(BoardError.RateLimited(null), classify(status(403, "x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "$past")))
        assertEquals(
            BoardError.RateLimited(NOW.plusSeconds(20)),
            classify(status(429, "x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "$past", "retry-after" to "20")),
        )
        assertEquals(BoardError.RateLimited(null), classify(status(403, "x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "${NOW.epochSecond}")))
    }

    @Test fun oddRateLimitHeaderValues() {
        // retry-after as an HTTP date is not supported: 403 falls back to the token message, 429 stays rate-limited.
        val date = "Wed, 21 Oct 2026 07:28:00 GMT"
        assertEquals(BoardError.BadToken(403), classify(status(403, "retry-after" to date)))
        assertEquals(BoardError.RateLimited(null), classify(status(429, "retry-after" to date)))
        assertEquals(BoardError.RateLimited(NOW), classify(status(429, "retry-after" to "0")))
        assertEquals(BoardError.BadToken(403), classify(status(403, "x-ratelimit-remaining" to "-1")))
        assertEquals(BoardError.BadToken(403), classify(status(403, "x-ratelimit-remaining" to "")))
        assertEquals(BoardError.RateLimited(null), classify(status(403, "x-ratelimit-remaining" to " 0 ", "x-ratelimit-reset" to "99999999999999999999")))
    }

    @Test fun approveClassifiesAMissingIssueSeparately() {
        listOf(301, 404, 410).forEach { code ->
            val http = FakeHttp().onUrlContaining("/issues/77/labels", status(code))
            val error = api(http).approve(77).error()
            assertEquals("HTTP $code", BoardError.IssueGone(77), error)
        }
        // Everything else is classified as usual.
        val limited = FakeHttp().onUrlContaining("labels", status(403, "x-ratelimit-remaining" to "0"))
        assertEquals(BoardError.RateLimited(null), api(limited).approve(77).error())
        val invalid = FakeHttp().onUrlContaining("labels", status(422))
        assertEquals(BoardError.Unexpected("HTTP 422"), api(invalid).approve(77).error())
        val expired = FakeHttp().onUrlContaining("labels", status(401))
        assertEquals(BoardError.BadToken(401), api(expired).approve(77).error())
    }

    @Test fun listing404IsStillNoAccess() {
        val http = FakeHttp().onUrlContaining("issues", status(404))
        assertEquals(BoardError.NoAccess, api(http).fetchIssues().error())
    }

    @Test fun issueGoneMessageIsSingleLineAndRetriesOnTap() {
        val messages = ErrorMessages { "14:05" }
        val m = messages.widgetMessage(BoardError.IssueGone(77))
        assertTrue(m.contains("#77"))
        assertFalse(m.contains('\n'))
        assertTrue(m.endsWith("tap to retry"))
        assertFalse(messages.opensSetup(BoardError.IssueGone(77)))
    }

    @Test fun socketTimeoutsAreTimeoutErrors() {
        listOf(SocketTimeoutException("Read timed out"), SocketTimeoutException("connect timed out")).forEach { e ->
            val http = FakeHttp().on({ true }) { throw e }
            assertEquals(BoardError.Timeout, api(http).fetchIssues().error())
            assertEquals(BoardError.Timeout, api(http).approve(3).error())
        }
    }

    @Test fun dnsAndTlsFailuresAreNetworkErrors() {
        listOf(UnknownHostException("api.github.com"), SSLHandshakeException("cert")).forEach { e ->
            val http = FakeHttp().on({ true }) { throw e }
            assertEquals(e.javaClass.simpleName, BoardError.Network, api(http).fetchIssues().error())
        }
    }

    @Test fun timeoutOnALaterPageFailsTheWholeFetch() {
        val next = "$apiBase/repositories/9/issues?page=2"
        val http = FakeHttp()
            .on({ "page=2" in it.url }) { throw SocketTimeoutException("timeout") }
            .onUrlContaining("state=open", ok(page(issueJson(2)), "Link" to "<$next>; rel=\"next\""))
        assertEquals(BoardError.Timeout, api(http).fetchIssues().error())
    }

    @Test fun unexpectedRuntimeExceptionFromTheHttpStackIsReportedNotThrown() {
        val http = FakeHttp().on({ true }) { throw SecurityException("Permission denied (missing INTERNET permission?)") }
        assertEquals(BoardError.Unexpected("request failed: SecurityException"), api(http).fetchIssues().error())
        assertEquals(BoardError.Unexpected("request failed: SecurityException"), api(http).approve(3).error())
    }

    @Test fun errorDetailsNeverContainTheToken() {
        val http = FakeHttp().on({ true }) { throw IllegalStateException("leaked $TOKEN") }
        val error = api(http).fetchIssues().error() as BoardError.Unexpected
        assertFalse(error.detail.contains(TOKEN))
    }

    // --- pagination --------------------------------------------------------------------------

    @Test fun threeFullPagesAreFetchedAndAllIssuesReachTheBoard() {
        val pages = (0 until 3).map { p -> (1..100).map { i -> issueJson(p * 100 + i + 1, "status: todo") } }
        val http = FakeHttp()
            .on({ "&page=" in it.url }) { r ->
                val p = r.url.substringAfter("&page=").toInt()
                val link = if (p < 3) "<$apiBase/repositories/9/issues?state=open&page=${p + 1}>; rel=\"next\"" else null
                ok(page(*pages[p - 1].toTypedArray()), *listOfNotNull(link?.let { "Link" to it }).toTypedArray())
            }
            .onUrlContaining("state=open", ok(page(*pages[0].toTypedArray()), "Link" to "<$apiBase/repositories/9/issues?state=open&page=2>; rel=\"next\""))
            .onUrlContaining("state=closed", ok("[]"))
        val issues = (api(http).fetchIssues() as Outcome.Success).value
        assertEquals(300, issues.size)
        assertEquals(300, issues.map { it.number }.toSet().size)
        val board = BoardBuilder().build(issues, NOW)
        assertEquals(300, board.section(Section.TODO).totalCount)
        assertEquals(50, board.section(Section.TODO).items.size)
    }

    @Test fun doneListingIsPaginatedToo() {
        val next = "$apiBase/repositories/9/issues?state=closed&page=2"
        val http = FakeHttp()
            .onUrlContaining("state=open", ok("[]"))
            .onUrlContaining("page=2", ok(page(issueJson(31, "status: done", state = "closed", closedAt = "2026-10-21T00:00:00Z"))))
            .onUrlContaining("state=closed", ok(page(issueJson(30, "status: done", state = "closed", closedAt = "2026-10-21T00:00:00Z")), "Link" to "<$next>; rel=\"next\""))
        val issues = (api(http).fetchIssues() as Outcome.Success).value
        assertEquals(2, BoardBuilder().build(issues, NOW).doneLast7Days)
    }

    @Test fun pagedFetcherReportsTruncation() {
        var n = 0
        val fetcher = PagedFetcher(2, { HttpRequest("GET", it) }) {
            n++
            Outcome.Success(PagedFetcher.Page(listOf(issue(n + 1)), "<$apiBase/x?page=${n + 1}>; rel=\"next\""))
        }
        val result = (fetcher.fetchAll(HttpRequest("GET", "$apiBase/x")) as Outcome.Success).value
        assertTrue(result.truncated)
        assertEquals(2, result.issues.size)
    }

    @Test fun foreignOrDowngradedNextLinksAreRefusedBeforeAnyRequest() {
        listOf(
            "https://evil.example/repos/x/issues?page=2",
            "http://api.github.com/repositories/9/issues?page=2",
            "https://api.github.com.evil.example/x",
            "https://api.github.com@evil.example/x",
            "//evil.example/x",
            "/repositories/9/issues?page=2",
            "HTTPS://API.GITHUB.COM/x", // not the exact canonical prefix: refused rather than guessed
        ).forEach { link ->
            val http = FakeHttp()
                .onUrlContaining("state=open", ok(page(issueJson(2)), "Link" to "<$link>; rel=\"next\""))
                .onUrlContaining("state=closed", ok("[]"))
            assertEquals(link, BoardError.Unexpected("bad pagination link"), api(http).fetchIssues().error())
            assertEquals(link, 1, http.requests.size)
        }
    }

    @Test fun linkHeaderWithOnlyPrevOrLastIsTheLastPage() {
        assertNull(LinkHeader.nextUrl("<$apiBase/x?page=1>; rel=\"prev\", <$apiBase/x?page=1>; rel=\"first\""))
        assertNull(LinkHeader.nextUrl("<$apiBase/x?page=9>; rel=\"last\""))
        assertNull(LinkHeader.nextUrl("<$apiBase/x?page=2>; rel=\"nextpage\""))
        assertNull(LinkHeader.nextUrl("<$apiBase/x?page=2>; title=\"next\""))
        assertNull(LinkHeader.nextUrl(",,,"))
        assertNull(LinkHeader.nextUrl("<<>>; rel=next"))
        assertEquals("$apiBase/x?page=2", LinkHeader.nextUrl("<$apiBase/x?page=2>;rel=next"))
    }

    @Test fun hugeLinkHeaderIsHandled() {
        val junk = (1..5_000).joinToString(", ") { "<$apiBase/x?page=$it>; rel=\"other$it\"" }
        assertEquals("$apiBase/x?page=2", LinkHeader.nextUrl("$junk, <$apiBase/x?page=2>; rel=\"next\""))
    }

    @Test fun approveRequestCarriesTheTokenOnlyToGitHub() {
        val http = FakeHttp().onUrlContaining("labels", HttpResponse(200, body = "[]"))
        api(http).approve(2)
        val request = http.requests.single()
        assertTrue(request.url.startsWith("$apiBase/repos/VeganMarshmallow/claude-workspace/issues/2/labels"))
        assertEquals("Bearer $TOKEN", request.headers["Authorization"])
    }

    @Test fun approveSuccessStatusesAreAccepted() {
        listOf(200, 201, 204).forEach { code ->
            val http = FakeHttp().onUrlContaining("labels", HttpResponse(code, body = ""))
            assertEquals(Outcome.Success(Unit), api(http).approve(2))
        }
    }

    @Test fun redirectOnListingIsUnexpectedNotFollowed() {
        val http = FakeHttp().onUrlContaining("issues", status(301, "Location" to "https://evil.example/"))
        assertEquals(BoardError.Unexpected("HTTP 301"), api(http).fetchIssues().error())
        assertEquals(1, http.requests.size)
    }

    @Test fun rateLimitedRetryTimeShowsInTheMessage() {
        val reset = Instant.parse("2026-10-22T12:30:00Z")
        val error = classify(status(403, "x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "${reset.epochSecond}"))
        assertEquals("Rate-limited by GitHub, retry after T", ErrorMessages { if (it == reset) "T" else "?" }.message(error!!))
    }
}
