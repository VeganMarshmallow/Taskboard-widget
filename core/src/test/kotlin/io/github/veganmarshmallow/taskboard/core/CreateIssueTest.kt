package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.task.CreatedIssue
import io.github.veganmarshmallow.taskboard.core.task.IssueResponseParser
import io.github.veganmarshmallow.taskboard.core.task.NewIssue
import io.github.veganmarshmallow.taskboard.core.task.SubmitGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class CreateIssueTest {
    private val issue = NewIssue("כותרת \"quoted\" \\ `x`", "### Repo\n\n_No response_", listOf(Labels.TODO, "type: bug"))
    private val created = """{"number":42,"labels":[{"name":"status: todo"},{"name":"Type: Bug"}]}"""

    private fun api(http: FakeHttp) = GitHubApi(http, TOKEN, FIXED_CLOCK)
    private fun Outcome<*>.error() = (this as Outcome.Failure).error

    @Test fun postsTitleBodyAndLabelsAsJson() {
        val http = FakeHttp().on({ it.method == "POST" }) { HttpResponse(201, body = created) }
        val result = api(http).createIssue(issue) as Outcome.Success
        assertEquals(CreatedIssue(42), result.value)
        val request = http.requests.single()
        assertEquals("https://api.github.com/repos/VeganMarshmallow/claude-workspace/issues", request.url)
        assertEquals("Bearer $TOKEN", request.headers["Authorization"])
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
        val json = JsonParser.parse(request.body!!) as Map<*, *>
        assertEquals(issue.title, json["title"])
        assertEquals(issue.body, json["body"])
        assertEquals(issue.labels, json["labels"])
    }

    @Test fun reportsLabelsGitHubDropped() {
        val http = FakeHttp().on({ true }) { HttpResponse(201, body = """{"number":7,"labels":[]}""") }
        assertEquals(CreatedIssue(7, issue.labels), (api(http).createIssue(issue) as Outcome.Success).value)
    }

    @Test fun unreadableSuccessStillSucceeds() {
        listOf("", "not json", "[]", """{"number":"x"}""", """{"number":0}""", """{"number":1.5}""").forEach { body ->
            val http = FakeHttp().on({ true }) { HttpResponse(201, body = body) }
            assertNull(body, ((api(http).createIssue(issue) as Outcome.Success).value).number)
        }
    }

    @Test fun errorsReuseTheBoardClassification() {
        fun err(r: HttpResponse) = api(FakeHttp().on({ true }) { r }).createIssue(issue).error()
        assertEquals(BoardError.BadToken(401), err(status(401)))
        assertEquals(BoardError.BadToken(403), err(status(403)))
        assertEquals(BoardError.NoAccess, err(status(404)))
        assertTrue(err(status(403, "x-ratelimit-remaining" to "0")) is BoardError.RateLimited)
        assertTrue(err(status(403, body = """{"message":"You have exceeded a secondary rate limit"}""")) is BoardError.RateLimited)
        assertEquals(BoardError.RateLimited(null), err(status(429)))
        assertEquals(BoardError.Server(502), err(status(502)))
    }

    @Test fun validationAndDisabledIssuesCarryGitHubsReason() {
        fun err(r: HttpResponse) = api(FakeHttp().on({ true }) { r }).createIssue(issue).error()
        val e422 = err(status(422, body = """{"message":"Validation Failed","errors":[{"resource":"Issue","code":"custom","field":"title","message":"title is too long"}]}"""))
        assertEquals(BoardError.Unexpected("HTTP 422: Validation Failed: title is too long"), e422)
        assertEquals(BoardError.Unexpected("HTTP 422: Validation Failed: title missing_field"),
            err(status(422, body = """{"message":"Validation Failed","errors":[{"field":"title","code":"missing_field"}]}""")))
        assertEquals(BoardError.Unexpected("HTTP 422: GitHub rejected the task"), err(status(422, body = "<html>")))
        assertTrue((err(status(410)) as BoardError.Unexpected).detail.contains("disabled"))
    }

    @Test fun networkFailuresAreNetworkErrors() {
        assertEquals(BoardError.Network, api(FakeHttp().on({ true }) { throw IOException("down") }).createIssue(issue).error())
        assertEquals(BoardError.Network, api(FakeHttp().on({ true }) { throw SocketTimeoutException() }).createIssue(issue).error())
        assertTrue(api(FakeHttp().on({ true }) { throw SecurityException() }).createIssue(issue).error() is BoardError.Unexpected)
    }

    @Test fun createIsNeverRetried() {
        val http = FakeHttp().on({ true }) { throw IOException("reset") }
        api(http).createIssue(issue)
        assertEquals(1, http.requests.size)
    }

    @Test fun repoSuggestionsComeFromRecentIssueBodies() {
        val body = """[{"number":3,"body":"### Repo\n\nme/app\n"},{"number":2,"body":null},{"number":4,"body":"Repo: me/app"},
            {"number":5,"body":"### Repo\n\nme/pr","pull_request":{"url":"x"}},{"number":6,"body":"Repo: o/x"}]"""
        val http = FakeHttp().onUrlContaining("state=all", ok(body))
        assertEquals(listOf("me/app", "o/x"), (api(http).repoSuggestions() as Outcome.Success).value)
        assertTrue(http.requests.single().url.contains("per_page=100"))
        assertEquals(BoardError.NoAccess, api(FakeHttp().on({ true }) { status(404) }).repoSuggestions().error())
        assertEquals(emptyList<String>(), (api(FakeHttp().on({ true }) { ok("garbage") }).repoSuggestions() as Outcome.Success).value)
    }

    @Test fun errorMessageParsing() {
        assertNull(IssueResponseParser.errorMessage(""))
        assertNull(IssueResponseParser.errorMessage("""{"x":1}"""))
        assertEquals("Bad", IssueResponseParser.errorMessage("""{"message":"Bad","errors":[]}"""))
        assertEquals("Bad: e", IssueResponseParser.errorMessage("""{"message":"Bad","errors":["e"]}"""))
        assertEquals(200, IssueResponseParser.errorMessage("""{"message":"${"m".repeat(500)}"}""")!!.length)
    }

    @Test fun submitGuardAllowsOneAtATime() {
        val guard = SubmitGuard()
        assertTrue(guard.tryStart())
        assertTrue(guard.isBusy)
        assertFalse(guard.tryStart())
        guard.finish()
        assertFalse(guard.isBusy)
        assertTrue(guard.tryStart())
    }

    @Test fun submitGuardUnderConcurrency() {
        val guard = SubmitGuard()
        val wins = java.util.concurrent.atomic.AtomicInteger()
        val threads = (1..16).map { Thread { if (guard.tryStart()) wins.incrementAndGet() } }
        threads.forEach(Thread::start); threads.forEach(Thread::join)
        assertEquals(1, wins.get())
    }
}
