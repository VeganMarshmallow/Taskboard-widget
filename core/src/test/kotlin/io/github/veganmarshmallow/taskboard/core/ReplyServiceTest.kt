package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.model.Repo
import io.github.veganmarshmallow.taskboard.core.reply.ReplyAction
import io.github.veganmarshmallow.taskboard.core.reply.ReplyResult
import io.github.veganmarshmallow.taskboard.core.reply.ReplyService
import io.github.veganmarshmallow.taskboard.core.reply.ReplyStep
import io.github.veganmarshmallow.taskboard.core.reply.ReplyText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/** Task #44: replying to Claude from the preview, against a scripted GitHub. */
class ReplyServiceTest {
    private val base = "https://api.github.com/repos/VeganMarshmallow/claude-workspace/issues/7"
    private val commentUrl = "$base/comments"
    private val labelsUrl = "$base/labels"
    private val removeNeedsOwner = "$labelsUrl/needs-owner"
    private val removeBlocked = "$labelsUrl/status%3A%20blocked"

    private fun service(http: FakeHttp, repo: Repo = Repo.BOARD) = ReplyService(GitHubApi(http, TOKEN, FIXED_CLOCK, repo))

    /** GitHub that accepts everything; [override] answers first for the requests it matches. */
    private fun github(override: Pair<(HttpRequest) -> Boolean, Int>? = null): FakeHttp = FakeHttp().apply {
        override?.let { (match, code) -> on(match) { status(code) } }
        on({ it.method == "POST" && it.url == commentUrl }) { status(201, body = "{\"id\":1}") }
        on({ it.method == "POST" && it.url == labelsUrl }) { ok("[]") }
        on({ it.method == "DELETE" && it.url.startsWith("$labelsUrl/") }) { ok("[]") }
    }

    private fun HttpRequest.jsonBody(): Map<*, *> = JsonParser.parse(body!!) as Map<*, *>
    private fun FakeHttp.calls() = requests.map { "${it.method} ${it.url}" }

    @Test fun postsTheCommentOnlyWhenNotUnblocking() {
        val http = github()
        assertEquals(ReplyResult.Sent(unblocked = false), service(http).send(7, ReplyAction.Post("Use ECharts", unblock = false)))
        assertEquals(listOf("POST $commentUrl"), http.calls())
        assertEquals("Use ECharts", http.requests.single().jsonBody()["body"])
        assertEquals("Bearer $TOKEN", http.requests.single().headers["Authorization"])
    }

    @Test fun unblockAddsTodoThenRemovesNeedsOwnerAndBlocked() {
        val http = github()
        assertEquals(ReplyResult.Sent(unblocked = true), service(http).send(7, ReplyAction.Post("ECharts", unblock = true)))
        assertEquals(
            listOf("POST $commentUrl", "POST $labelsUrl", "DELETE $removeNeedsOwner", "DELETE $removeBlocked"),
            http.calls(),
        )
        assertEquals(listOf("status: todo"), http.requests[1].jsonBody()["labels"])
    }

    @Test fun usesTheConfiguredBoardRepo() {
        val http = FakeHttp().on({ true }) { status(201, body = "{}") }
        service(http, Repo("someone", "other-board")).send(3, ReplyAction.Post("hi", unblock = true))
        assertTrue(http.requests.all { it.url.startsWith("https://api.github.com/repos/someone/other-board/issues/3/") })
    }

    @Test fun labelAlreadyGoneIsFine() {
        val http = github(Pair({ r: HttpRequest -> r.method == "DELETE" }, 404))
        assertEquals(ReplyResult.Sent(unblocked = true), service(http).send(7, ReplyAction.Post("x", unblock = true)))
    }

    @Test fun commentErrorsAreClassifiedLikeApprove() {
        val cases = mapOf(
            401 to BoardError.BadToken(401),
            403 to BoardError.BadToken(403),
            404 to BoardError.IssueGone(7),
            410 to BoardError.IssueGone(7),
            502 to BoardError.Server(502),
        )
        cases.forEach { (code, expected) ->
            val http = github(Pair({ r: HttpRequest -> r.url == commentUrl }, code))
            val result = service(http).send(7, ReplyAction.Post("x", unblock = true))
            assertEquals("HTTP $code", ReplyResult.Failed(ReplyStep.COMMENT, expected), result)
            assertEquals("no label change after a failed comment", 1, http.requests.size)
        }
    }

    @Test fun validationFailureCarriesGitHubsMessage() {
        val http = FakeHttp().on({ true }) {
            status(422, body = "{\"message\":\"Validation Failed\",\"errors\":[{\"field\":\"body\",\"code\":\"too_long\"}]}")
        }
        val failed = service(http).send(7, ReplyAction.Post("x", unblock = false)) as ReplyResult.Failed
        assertEquals(BoardError.Unexpected("HTTP 422: Validation Failed: body too_long"), failed.error)
        assertFalse(failed.mayHavePosted)
    }

    @Test fun lockedIssueIsNotReportedAsABadToken() {
        val http = FakeHttp().on({ true }) { status(403, body = "{\"message\":\"Unable to create comment because issue is locked.\"}") }
        val failed = service(http).send(7, ReplyAction.Post("x", unblock = false)) as ReplyResult.Failed
        assertEquals(BoardError.Unexpected("issue #7 is locked, comments are off"), failed.error)
    }

    @Test fun rateLimitedCommentStaysARateLimit() {
        val http = FakeHttp().on({ true }) { status(403, "x-ratelimit-remaining" to "0", body = "{\"message\":\"API rate limit exceeded\"}") }
        val failed = service(http).send(7, ReplyAction.Post("x", unblock = false)) as ReplyResult.Failed
        assertTrue(failed.error is BoardError.RateLimited)
    }

    @Test fun networkFailureAndTimeoutMayHavePosted() {
        val offline = FakeHttp() // no routes: every request throws IOException
        val failed = service(offline).send(7, ReplyAction.Post("x", unblock = true)) as ReplyResult.Failed
        assertEquals(ReplyResult.Failed(ReplyStep.COMMENT, BoardError.Network), failed)
        assertTrue(failed.mayHavePosted)

        val slow = FakeHttp().on({ true }) { throw SocketTimeoutException() }
        val timedOut = service(slow).send(7, ReplyAction.Post("x", unblock = false)) as ReplyResult.Failed
        assertEquals(BoardError.Timeout, timedOut.error)
        assertTrue(timedOut.mayHavePosted)
    }

    @Test fun labelStepFailureIsReportedSeparately() {
        listOf<(HttpRequest) -> Boolean>(
            { it.method == "POST" && it.url == labelsUrl },
            { it.url == removeNeedsOwner },
            { it.url == removeBlocked },
        ).forEach { failing ->
            val http = github(failing to 403)
            val failed = service(http).send(7, ReplyAction.Post("x", unblock = true)) as ReplyResult.Failed
            assertEquals(ReplyResult.Failed(ReplyStep.UNBLOCK, BoardError.BadToken(403)), failed)
            assertFalse("the comment is already posted", failed.mayHavePosted)
            assertEquals(1, http.requests.count { it.url == commentUrl })
        }
    }

    @Test fun retryingTheUnblockNeverPostsAgain() {
        val http = github()
        assertEquals(ReplyResult.Sent(unblocked = true), service(http).send(7, ReplyAction.Unblock))
        assertTrue(http.requests.none { it.url == commentUrl })
        assertEquals(3, http.requests.size)
    }

    @Test fun retriedUnblockFailureIsStillTheUnblockStep() {
        val failed = service(FakeHttp()).send(7, ReplyAction.Unblock) as ReplyResult.Failed
        assertEquals(ReplyResult.Failed(ReplyStep.UNBLOCK, BoardError.Network), failed)
    }

    @Test fun emptyOrWhitespaceIsNeverSent() {
        listOf("", "   ", "\n\t \n").forEach { text ->
            val http = github()
            val failed = service(http).send(7, ReplyAction.Post(text, unblock = true)) as ReplyResult.Failed
            assertEquals(ReplyStep.COMMENT, failed.step)
            assertTrue(http.requests.isEmpty())
        }
    }

    @Test fun veryLongText() {
        val atLimit = "a".repeat(ReplyText.MAX_LENGTH)
        val http = github()
        assertEquals(ReplyResult.Sent(false), service(http).send(7, ReplyAction.Post(atLimit, unblock = false)))
        assertEquals(atLimit, http.requests.single().jsonBody()["body"])

        val tooLong = github()
        val failed = service(tooLong).send(7, ReplyAction.Post(atLimit + "b", unblock = false)) as ReplyResult.Failed
        assertTrue(failed.error.toString(), (failed.error as BoardError.Unexpected).detail.contains("too long"))
        assertTrue(tooLong.requests.isEmpty())
    }

    @Test fun hebrewAndMultiLineTextIsPostedExactly() {
        val text = "כן, תשתמש ב־ECharts.\n\n```kotlin\nval x = \"quote\" \\ 1\n```\n🙂"
        val http = github()
        service(http).send(7, ReplyAction.Post("\n  $text  \n", unblock = false))
        assertEquals(text, http.requests.single().jsonBody()["body"])
        assertTrue(http.requests.single().headers["Content-Type"]!!.contains("utf-8"))
    }

    @Test fun crashInTheHttpStackBecomesAFailure() {
        val http = FakeHttp().on({ true }) { throw IllegalStateException("boom") }
        val failed = service(http).send(7, ReplyAction.Post("x", unblock = false)) as ReplyResult.Failed
        assertEquals(BoardError.Internal("IllegalStateException"), failed.error)
    }

    @Test fun approveStillAddsOnlyReadyForClaude() {
        val http = FakeHttp().on({ true }) { ok("[]") }
        GitHubApi(http, TOKEN, FIXED_CLOCK).approve(7)
        assertEquals("POST $labelsUrl", http.calls().single())
        assertEquals(listOf("ready-for-claude"), http.requests.single().jsonBody()["labels"])
    }
}
