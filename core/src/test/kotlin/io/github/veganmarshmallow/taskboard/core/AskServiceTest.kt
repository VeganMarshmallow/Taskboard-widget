package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.questions.AskService
import io.github.veganmarshmallow.taskboard.core.questions.QuestionsLoader
import io.github.veganmarshmallow.taskboard.core.reply.ReplyResult
import io.github.veganmarshmallow.taskboard.core.reply.ReplyText
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task #82: asking Claude and loading the Questions screen, against a scripted GitHub. */
class AskServiceTest {
    private val repo = "https://api.github.com/repos/VeganMarshmallow/claude-workspace"
    private fun api(http: FakeHttp) = GitHubApi(http, TOKEN, FIXED_CLOCK)
    private fun HttpRequest.json(): Map<*, *> = JsonParser.parse(body!!) as Map<*, *>
    private fun FakeHttp.calls() = requests.map { "${it.method} ${it.url}" }

    @Test fun askAboutATaskPostsTheCommentThenTheLabel() {
        val http = FakeHttp()
            .on({ it.method == "POST" && it.url == "$repo/issues/12/comments" }) { status(201, body = "{}") }
            .on({ it.method == "POST" && it.url == "$repo/issues/12/labels" }) { ok("[]") }
        assertEquals(ReplyResult.Sent(unblocked = false), AskService(api(http)).ask(12, " Why? "))
        assertEquals(listOf("POST $repo/issues/12/comments", "POST $repo/issues/12/labels"), http.calls())
        assertEquals("**Question for Claude:**\n\nWhy?", http.requests[0].json()["body"])
        assertEquals(listOf("question-for-claude"), http.requests[1].json()["labels"])
    }

    @Test fun aFailedLabelStillCountsAsAsked() {
        val http = FakeHttp()
            .on({ it.url.endsWith("/comments") }) { status(201, body = "{}") }
            .on({ it.url.endsWith("/labels") }) { status(500) }
        assertEquals(ReplyResult.Sent(unblocked = false), AskService(api(http)).ask(12, "Why?"))
    }

    @Test fun aFailedCommentIsReportedAndNoLabelIsAdded() {
        val http = FakeHttp().on({ it.url.endsWith("/comments") }) { status(502) }
        val result = AskService(api(http)).ask(12, "Why?") as ReplyResult.Failed
        assertEquals(BoardError.Server(502), result.error)
        assertTrue(result.mayHavePosted)
        assertEquals(1, http.requests.size)
    }

    @Test fun generalQuestionCreatesAnIssueNotReadyForClaude() {
        val http = FakeHttp().on({ it.method == "POST" && it.url == "$repo/issues" }) { status(201, body = """{"number":91,"labels":[]}""") }
        assertEquals(ReplyResult.Sent(unblocked = false), AskService(api(http)).ask(null, "How much CI is left?\nThanks"))
        val sent = http.requests.single().json()
        assertEquals("Question for Claude: How much CI is left?", sent["title"])
        assertEquals("**Question for Claude:**\n\nHow much CI is left?\nThanks", sent["body"])
        assertEquals(listOf("question-for-claude", "status: todo"), sent["labels"])
    }

    @Test fun emptyOrHugeQuestionsAreRefusedWithoutARequest() {
        val http = FakeHttp()
        assertTrue(AskService(api(http)).ask(12, "   ") is ReplyResult.Failed)
        assertTrue(AskService(api(http)).ask(null, "x".repeat(ReplyText.MAX_LENGTH)) is ReplyResult.Failed) // too long with the prefix
        assertTrue(http.requests.isEmpty())
    }

    @Test fun aCrashBecomesAFailure() {
        val http = FakeHttp().on({ true }) { throw IllegalStateException("boom") }
        assertTrue(AskService(api(http)).ask(12, "Why?") is ReplyResult.Failed)
    }

    @Test fun loaderReadsTasksAndRepoCommentsThenBackfillsOldQuestions() {
        val http = FakeHttp()
            .on({ "/issues/comments?" in it.url }) {
                ok("""[{"id":2,"body":"🤖 Which colour?","created_at":"2026-10-22T09:00:00Z","issue_url":"$repo/issues/7"}]""")
            }
            .on({ "/issues/5/comments" in it.url }) { ok("""[{"id":1,"body":"⛔ Blocked.\n\n**What I need from you:** a token","created_at":"2026-09-01T00:00:00Z"}]""") }
            .on({ "state=open" in it.url }) {
                ok("""[{"number":5,"title":"Old","state":"open","labels":[{"name":"needs-owner"}],"body":""},{"number":7,"title":"New","state":"open","labels":[],"body":""}]""")
            }
            .on({ "state=closed" in it.url }) { ok("[]") }
        val questions = (QuestionsLoader(api(http)).load() as Outcome.Success).value
        assertEquals(listOf(5, 7), questions.fromClaude.map { it.issue })
        assertEquals("a token", questions.fromClaude[0].message!!.needFromYou)
        assertTrue(http.requests.any { "sort=created&direction=desc&since=2026-09-22T12%3A00%3A00Z" in it.url })
    }

    @Test fun loaderFailsWithoutTokenOrWhenCommentsFail() {
        assertEquals(Outcome.Failure(BoardError.NoToken), QuestionsLoader(null).load())
        val http = FakeHttp()
            .on({ "/issues/comments?" in it.url }) { status(503) }
            .on({ "state=" in it.url }) { ok("[]") }
        assertEquals(Outcome.Failure(BoardError.Server(503)), QuestionsLoader(api(http)).load())
    }

    @Test fun aFailedBackfillOnlyLosesThatText() {
        val http = FakeHttp()
            .on({ "/issues/comments?" in it.url }) { ok("[]") }
            .on({ "/issues/5/comments" in it.url }) { status(500) }
            .on({ "state=open" in it.url }) { ok("""[{"number":5,"title":"Old","state":"open","labels":[{"name":"needs-owner"}],"body":""}]""") }
            .on({ "state=closed" in it.url }) { ok("[]") }
        val q = (QuestionsLoader(api(http)).load() as Outcome.Success).value.fromClaude.single()
        assertEquals(null, q.message)
    }
}
