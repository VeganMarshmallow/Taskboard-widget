package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GitHubApiTest {
    private val api0 = "https://api.github.com"
    private val nextOpen = "$api0/repositories/9/issues?state=open&per_page=100&page=2"

    private fun api(http: FakeHttp, maxPages: Int = 20) = GitHubApi(http, TOKEN, FIXED_CLOCK, maxPages = maxPages)

    private fun Outcome<*>.error() = (this as Outcome.Failure).error

    @Test fun fetchesOpenAndDoneAndFollowsPagination() {
        val http = FakeHttp()
            .onUrlContaining("page=2", ok(page(issueJson(3, "status: todo"))))
            .onUrlContaining("state=open", ok(page(issueJson(2, "status: todo")), "Link" to "<$nextOpen>; rel=\"next\""))
            .onUrlContaining("state=closed", ok(page(issueJson(9, "status: done", state = "closed", closedAt = "2026-10-21T00:00:00Z"))))
        val result = api(http).fetchIssues() as Outcome.Success
        assertEquals(listOf(2, 3, 9), result.value.map { it.number })
        assertEquals(3, http.requests.size)
        assertTrue(http.requests.all { it.headers["Authorization"] == "Bearer $TOKEN" })
    }

    @Test fun paginationStopsAtCap() {
        var n = 0
        val http = FakeHttp()
            .on({ "state=open" in it.url || "/repositories/" in it.url }) {
                n++
                ok(page(issueJson(n + 1)), "link" to "<$api0/repositories/9/issues?page=${n + 1}>; rel=\"next\"")
            }
            .onUrlContaining("state=closed", ok("[]"))
        val result = api(http, maxPages = 3).fetchIssues() as Outcome.Success
        assertEquals(3, result.value.size)
        assertEquals(3 + 1, http.requests.size)
    }

    @Test fun paginationLoopIsBroken() {
        val self = "$api0/repositories/9/issues?page=1"
        val http = FakeHttp()
            .on({ "state=open" in it.url || it.url == self }) { ok(page(issueJson(2)), "link" to "<$self>; rel=\"next\"") }
            .onUrlContaining("state=closed", ok("[]"))
        val result = api(http).fetchIssues() as Outcome.Success
        assertEquals(2, result.value.size) // first page + the self page once, then stop
    }

    @Test fun nonGitHubNextLinkIsRefused() {
        val http = FakeHttp()
            .onUrlContaining("state=open", ok(page(issueJson(2)), "link" to "<https://evil.example/steal>; rel=\"next\""))
        assertEquals(BoardError.Unexpected("bad pagination link"), api(http).fetchIssues().error())
        assertTrue(http.requests.none { "evil" in it.url })
    }

    @Test fun malformedLinkHeaderIsTreatedAsLastPage() {
        val http = FakeHttp()
            .onUrlContaining("state=open", ok(page(issueJson(2)), "link" to "this is not a link header"))
            .onUrlContaining("state=closed", ok("[]"))
        assertEquals(1, (api(http).fetchIssues() as Outcome.Success).value.size)
    }

    @Test fun emptyBoardIsSuccess() {
        val http = FakeHttp().onUrlContaining("issues", ok("[]"))
        assertEquals(emptyList<Any>(), (api(http).fetchIssues() as Outcome.Success).value)
    }

    @Test fun errorOnSecondPageFailsTheWholeFetch() {
        val http = FakeHttp()
            .onUrlContaining("page=2", status(502))
            .onUrlContaining("state=open", ok(page(issueJson(2)), "Link" to "<$nextOpen>; rel=\"next\""))
        assertEquals(BoardError.Server(502), api(http).fetchIssues().error())
    }

    @Test fun errorOnDoneListingFailsTheWholeFetch() {
        val http = FakeHttp()
            .onUrlContaining("state=open", ok("[]"))
            .onUrlContaining("state=closed", status(403, "x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "1792671300"))
        assertTrue(api(http).fetchIssues().error() is BoardError.RateLimited)
    }

    @Test fun statusErrorsAreClassified() {
        mapOf(
            status(401) to BoardError.BadToken(401),
            status(403) to BoardError.BadToken(403),
            status(404) to BoardError.NoAccess,
            status(429) to BoardError.RateLimited(null),
            status(500) to BoardError.Server(500),
        ).forEach { (response, expected) ->
            val http = FakeHttp().onUrlContaining("issues", response)
            assertEquals(expected, api(http).fetchIssues().error())
        }
    }

    @Test fun networkFailureIsNetworkError() {
        val http = FakeHttp().on({ true }) { throw IOException("Unable to resolve host") }
        assertEquals(BoardError.Network, api(http).fetchIssues().error())
        assertEquals(BoardError.Network, api(http).approve(4).error())
        assertEquals(BoardError.Network, api(http).testConnection().error())
    }

    @Test fun malformedJsonIsUnexpected() {
        listOf("<html>502</html>", "{\"message\":\"x\"}", "", "[{\"nope\":1}]").forEach { body ->
            val http = FakeHttp().onUrlContaining("issues", HttpResponse(200, body = body))
            assertTrue("body: $body", api(http).fetchIssues().error() is BoardError.Unexpected)
        }
    }

    @Test fun approveSendsLabelAndClassifiesErrors() {
        val http = FakeHttp().onUrlContaining("/issues/6/labels", HttpResponse(200, body = "[]"))
        assertEquals(Outcome.Success(Unit), api(http).approve(6))
        assertEquals("POST", http.requests.single().method)
        assertEquals("{\"labels\":[\"ready-for-claude\"]}", http.requests.single().body)

        val denied = FakeHttp().onUrlContaining("labels", status(403))
        assertEquals(BoardError.BadToken(403), api(denied).approve(6).error())
        val gone = FakeHttp().onUrlContaining("labels", status(404))
        assertEquals(BoardError.IssueGone(6), api(gone).approve(6).error())
    }

    @Test fun testConnectionChecksRepoThenIssues() {
        val good = FakeHttp()
            .onUrlContaining("/issues", ok("[]"))
            .onUrlContaining("/repos/VeganMarshmallow/claude-workspace", ok("{\"id\":1}"))
        assertEquals(Outcome.Success(Unit), api(good).testConnection())
        assertEquals(2, good.requests.size)

        val noRepo = FakeHttp().onUrlContaining("/repos/", status(404))
        assertEquals(BoardError.NoAccess, api(noRepo).testConnection().error())
        assertEquals(1, noRepo.requests.size)

        val noIssues = FakeHttp()
            .onUrlContaining("/issues", status(403))
            .onUrlContaining("/repos/", ok("{}"))
        assertEquals(BoardError.BadToken(403), api(noIssues).testConnection().error())
    }
}
