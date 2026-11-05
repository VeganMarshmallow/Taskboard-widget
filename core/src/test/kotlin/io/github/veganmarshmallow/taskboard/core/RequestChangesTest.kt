package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.json.JsonWriter
import io.github.veganmarshmallow.taskboard.core.merge.FoundPr
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.merge.MergeStep
import io.github.veganmarshmallow.taskboard.core.merge.PullRequest
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.preview.IssueDetails
import io.github.veganmarshmallow.taskboard.core.preview.PreviewScreen
import io.github.veganmarshmallow.taskboard.core.reply.ChangeRequestText
import io.github.veganmarshmallow.taskboard.core.reply.ReplyAction
import io.github.veganmarshmallow.taskboard.core.reply.ReplyForm
import io.github.veganmarshmallow.taskboard.core.reply.ReplyMemory
import io.github.veganmarshmallow.taskboard.core.reply.ReplyResult
import io.github.veganmarshmallow.taskboard.core.reply.ReplyService
import io.github.veganmarshmallow.taskboard.core.reply.ReplyStep
import io.github.veganmarshmallow.taskboard.core.reply.ReplyText
import io.github.veganmarshmallow.taskboard.core.reply.StatusMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task #65: "Request changes" sends an in-review task back to work on its PR. */
class RequestChangesTest {
    private val base = "https://api.github.com/repos/VeganMarshmallow/claude-workspace/issues/7"
    private val commentUrl = "$base/comments"
    private val labelsUrl = "$base/labels"
    private val removeInReview = "$labelsUrl/status%3A%20in-review"
    private val widgetRepo = "$API_REPOS/VeganMarshmallow/Taskboard-widget"
    private val thePr = pr(12, head = "claude/7-charts")

    /** GitHub that accepts every write; [override] answers first for the requests it matches. */
    private fun github(override: Pair<(HttpRequest) -> Boolean, Int>? = null): FakeHttp = FakeHttp().apply {
        override?.let { (match, code) -> on(match) { status(code) } }
        on({ it.method == "POST" && it.url == commentUrl }) { status(201, body = "{\"id\":1}") }
        on({ it.method == "POST" && it.url == labelsUrl }) { ok("[]") }
        on({ it.method == "DELETE" && it.url.startsWith("$labelsUrl/") }) { ok("[]") }
    }

    private val lookups = mutableListOf<Int>()

    private fun service(http: FakeHttp, found: PullRequest? = thePr) =
        ReplyService(GitHubApi(http, TOKEN, FIXED_CLOCK)) { issue -> lookups += issue; found }

    private fun FakeHttp.calls() = requests.map { "${it.method} ${it.url}" }
    private fun HttpRequest.jsonBody(): Map<*, *> = JsonParser.parse(body!!) as Map<*, *>
    private fun FakeHttp.postedComment(): String =
        requests.single { it.method == "POST" && it.url == commentUrl }.jsonBody()["body"] as String

    // --- the comment's text ---

    @Test fun commentStartsWithThePrefixThenTheTextThenThePrLine() {
        val body = ChangeRequestText.comment("Make the button blue", thePr)
        assertTrue(body, body.startsWith("**Changes requested by the owner:**\n\nMake the button blue\n\n"))
        val nextRun = body.substringAfterLast("\n\n")
        assertTrue(nextRun, nextRun.contains("VeganMarshmallow/Taskboard-widget#12"))
        assertTrue(nextRun, nextRun.contains("`claude/7-charts`"))
        assertTrue(nextRun, nextRun.contains("don't open a new PR"))
        assertTrue(nextRun, nextRun.contains("`status: in-review`"))
    }

    @Test fun unknownPrStillSaysNotToOpenANewOne() {
        val line = ChangeRequestText.nextRun(null)
        assertTrue(line, line.contains("existing PR"))
        assertTrue(line, line.contains("don't open a new PR"))
        assertFalse(line, line.contains("#"))
    }

    @Test fun branchWithABacktickStaysOneCodeSpan() {
        val line = ChangeRequestText.nextRun(pr(12, head = "odd`branch"))
        assertTrue(line, line.contains("`` odd`branch ``"))
    }

    @Test fun prIsTheOneMergeWouldPickOrTheSingleUnlinkedOne() {
        assertEquals(thePr, ChangeRequestText.prOf(MergeStep.Ok(FoundPr(thePr, null))))
        assertEquals(thePr, ChangeRequestText.prOf(MergeStep.Stop(MergeProblem.NotLinked(listOf(thePr)))))
        assertNull(ChangeRequestText.prOf(MergeStep.Stop(MergeProblem.NotLinked(listOf(pr(1), pr(2))))))
        assertNull(ChangeRequestText.prOf(MergeStep.Stop(MergeProblem.Ambiguous(listOf(pr(1), pr(2))))))
        assertNull(ChangeRequestText.prOf(MergeStep.Stop(MergeProblem.NotFound)))
        assertNull(ChangeRequestText.prOf(MergeStep.Stop(MergeProblem.Failed(BoardError.Network))))
        assertNull(ChangeRequestText.prOf(MergeStep.Ok(FoundPr(pr(12, open = false), null))))
    }

    // --- the service ---

    @Test fun requestChangesPostsThePrefixedCommentAndMovesInReviewToTodo() {
        val http = github()
        val result = service(http).send(7, ReplyAction.Post("  Make the button blue \n", StatusMove.REQUEST_CHANGES))
        assertEquals(ReplyResult.Sent(unblocked = false, changesRequested = true), result)
        assertEquals(listOf("POST $commentUrl", "POST $labelsUrl", "DELETE $removeInReview"), http.calls())
        assertEquals(ChangeRequestText.comment("Make the button blue", thePr), http.postedComment())
        assertEquals(listOf(Labels.TODO), http.requests[1].jsonBody()["labels"])
        assertEquals(listOf(7), lookups)
    }

    @Test fun plainSendPostsTheTextAloneAndLeavesLabelsAlone() {
        val http = github()
        assertEquals(ReplyResult.Sent(unblocked = false), service(http).send(7, ReplyAction.Post("Looks good", move = null)))
        assertEquals(listOf("POST $commentUrl"), http.calls())
        assertEquals("Looks good", http.postedComment())
        assertTrue("no PR lookup for a plain reply", lookups.isEmpty())
    }

    @Test fun unblockNeverLooksForAPrOrAddsThePrefix() {
        val http = github()
        service(http).send(7, ReplyAction.Post("Use ECharts", unblock = true))
        assertEquals("Use ECharts", http.postedComment())
        assertTrue(lookups.isEmpty())
        assertTrue(http.calls().none { it == "DELETE $removeInReview" })
    }

    @Test fun prNotFoundStillPostsTheRequest() {
        val http = github()
        val result = service(http, found = null).send(7, ReplyAction.Post("Fix it", StatusMove.REQUEST_CHANGES))
        assertEquals(ReplyResult.Sent(unblocked = false, changesRequested = true), result)
        assertEquals(ChangeRequestText.comment("Fix it", null), http.postedComment())
    }

    @Test fun emptyTextIsNeverSentNorLookedUp() {
        val http = github()
        val failed = service(http).send(7, ReplyAction.Post("  \n ", StatusMove.REQUEST_CHANGES)) as ReplyResult.Failed
        assertEquals(ReplyStep.COMMENT, failed.step)
        assertTrue(http.requests.isEmpty())
        assertTrue(lookups.isEmpty())
    }

    @Test fun textThatOnlyFitsWithoutThePrLineIsRefusedBeforePosting() {
        val http = github()
        val failed = service(http).send(7, ReplyAction.Post("a".repeat(ReplyText.MAX_LENGTH), StatusMove.REQUEST_CHANGES)) as ReplyResult.Failed
        assertEquals(ReplyStep.COMMENT, failed.step)
        assertTrue(http.requests.isEmpty())
    }

    @Test fun commentFailureChangesNoLabels() {
        val http = github({ r: HttpRequest -> r.url == commentUrl } to 502)
        val failed = service(http).send(7, ReplyAction.Post("Fix it", StatusMove.REQUEST_CHANGES)) as ReplyResult.Failed
        assertEquals(ReplyStep.COMMENT, failed.step)
        assertEquals(listOf("POST $commentUrl"), http.calls())
    }

    @Test fun labelFailureIsTheRequestChangesStepAndItsRetryNeverPostsAgain() {
        listOf<(HttpRequest) -> Boolean>({ it.method == "POST" && it.url == labelsUrl }, { it.url == removeInReview }).forEach { failing ->
            val http = github(failing to 403)
            val failed = service(http).send(7, ReplyAction.Post("Fix it", StatusMove.REQUEST_CHANGES)) as ReplyResult.Failed
            assertEquals(ReplyResult.Failed(ReplyStep.REQUEST_CHANGES, BoardError.BadToken(403)), failed)
            assertTrue("the comment is on GitHub", failed.posted)
        }
        val http = github()
        assertEquals(
            ReplyResult.Sent(unblocked = false, changesRequested = true),
            service(http).send(7, ReplyAction.Move(StatusMove.REQUEST_CHANGES)),
        )
        assertEquals(listOf("POST $labelsUrl", "DELETE $removeInReview"), http.calls())
    }

    @Test fun crashDuringTheRetryIsStillTheRequestChangesStep() {
        val http = FakeHttp().on({ true }) { throw IllegalStateException("boom") }
        val failed = service(http).send(7, ReplyAction.Move(StatusMove.REQUEST_CHANGES)) as ReplyResult.Failed
        assertEquals(ReplyStep.REQUEST_CHANGES, failed.step)
    }

    @Test fun defaultLookupNamesThePrTheMergeSearchFinds() {
        val issueBody = "### Repo\n\nVeganMarshmallow/Taskboard-widget\n\n### What should be done\n\nCharts"
        val http = github().apply {
            on({ it.method == "GET" && it.url == base }) { ok(JsonWriter.write(issueJson(7, Labels.IN_REVIEW) + ("body" to issueBody))) }
            on({ it.method == "GET" && it.url.startsWith(commentUrl) }) { ok(commentsJson("🤖 Started")) }
            on({ it.method == "GET" && it.url.startsWith("$widgetRepo/pulls?state=open") }) {
                ok(pullsPage(pullJson(31, body = "Closes VeganMarshmallow/claude-workspace#7", head = "claude/7-charts")))
            }
        }
        ReplyService(GitHubApi(http, TOKEN, FIXED_CLOCK)).send(7, ReplyAction.Post("Fix it", StatusMove.REQUEST_CHANGES))
        val nextRun = http.postedComment().substringAfterLast("\n\n")
        assertTrue(nextRun, nextRun.contains("VeganMarshmallow/Taskboard-widget#31"))
        assertTrue(nextRun, nextRun.contains("`claude/7-charts`"))
    }

    @Test fun failedLookupStillPostsWithoutAPrNumber() {
        val http = github() // the GETs have no route: the lookup fails like a lost connection
        val result = ReplyService(GitHubApi(http, TOKEN, FIXED_CLOCK)).send(7, ReplyAction.Post("Fix it", StatusMove.REQUEST_CHANGES))
        assertEquals(ReplyResult.Sent(unblocked = false, changesRequested = true), result)
        assertEquals(ChangeRequestText.comment("Fix it", null), http.postedComment())
    }

    // --- the form ---

    @Test fun checkedAndOfferedSendsTheRequest() {
        val form = ReplyForm().edit("Fix it").requestChangesChecked(true)
        assertEquals(ReplyAction.Post("Fix it", StatusMove.REQUEST_CHANGES), form.start(offerUnblock = false, offerRequestChanges = true)!!.action)
        assertEquals(ReplyAction.Post("Fix it", null), form.start(offerUnblock = false, offerRequestChanges = false)!!.action)
        assertEquals(
            ReplyAction.Post("Fix it", null),
            form.requestChangesChecked(false).start(offerUnblock = false, offerRequestChanges = true)!!.action,
        )
    }

    @Test fun requestChangesIsOffByDefault() {
        assertFalse(ReplyForm().requestChanges)
        assertEquals(ReplyAction.Post("ok", null), ReplyForm().edit("ok").start(false, offerRequestChanges = true)!!.action)
    }

    @Test fun unblockWinsWhenBothAreOffered() {
        val form = ReplyForm().edit("x").requestChangesChecked(true)
        assertEquals(StatusMove.UNBLOCK, (form.start(offerUnblock = true, offerRequestChanges = true)!!.action as ReplyAction.Post).move)
    }

    @Test fun doubleTapSendsOnce() {
        val started = ReplyForm().edit("Fix it").requestChangesChecked(true).start(false, true)!!
        assertNull(started.form.start(false, true))
        assertFalse(started.form.canSend)
    }

    @Test fun commentErrorKeepsTheTextAndTheChoice() {
        val failed = ReplyForm().edit("Fix it").requestChangesChecked(true).start(false, true)!!.form
            .finished(ReplyResult.Failed(ReplyStep.COMMENT, BoardError.Network))
        assertEquals("Fix it", failed.draft)
        assertTrue(failed.requestChanges)
        assertFalse(failed.sending)
        assertNull(failed.pendingMove)
        assertEquals(ReplyAction.Post("Fix it", StatusMove.REQUEST_CHANGES), failed.start(false, true)!!.action)
    }

    @Test fun labelErrorRetriesOnlyTheSendBack() {
        val failed = ReplyForm().edit("Fix it").requestChangesChecked(true).start(false, true)!!.form
            .finished(ReplyResult.Failed(ReplyStep.REQUEST_CHANGES, BoardError.Server(502)))
        assertEquals("", failed.draft)
        assertEquals(StatusMove.REQUEST_CHANGES, failed.pendingMove)
        assertTrue(failed.retriesLabels)
        assertEquals(ReplyAction.Move(StatusMove.REQUEST_CHANGES), failed.start(false, false)!!.action)
        assertEquals(
            ReplyAction.Post("also this", StatusMove.REQUEST_CHANGES),
            failed.edit("also this").start(false, false)!!.action,
        )
    }

    @Test fun successClearsTheTextAndTheChoice() {
        val done = ReplyForm().edit("Fix it").requestChangesChecked(true).start(false, true)!!.form
            .finished(ReplyResult.Sent(unblocked = false, changesRequested = true))
        assertEquals("", done.draft)
        assertFalse(done.requestChanges)
        assertNull(done.pendingMove)
        assertTrue(done.sent!!.changesRequested)
    }

    @Test fun reopenedSheetKeepsTheDraftAndCannotSendTwice() {
        val memory = ReplyMemory()
        val sending = ReplyForm().edit("Fix it").requestChangesChecked(true).start(false, true)!!.form
        memory.remember(7, sending)
        assertNull("a send is still in flight", memory.form(7).start(false, true))
        memory.finished(7, ReplyResult.Failed(ReplyStep.COMMENT, BoardError.Timeout))
        assertEquals("Fix it", memory.form(7).draft)
        assertTrue(memory.form(7).requestChanges)
    }

    @Test fun stepsAndMovesMatch() {
        StatusMove.entries.forEach { assertEquals(it, it.step.move) }
        assertNull(ReplyStep.COMMENT.move)
        assertEquals(ReplyStep.REQUEST_CHANGES, ReplyAction.Move(StatusMove.REQUEST_CHANGES).step)
        assertEquals(ReplyStep.COMMENT, ReplyAction.Post("x", StatusMove.REQUEST_CHANGES).step)
    }

    // --- when the choice is offered ---

    private fun details(vararg labels: String) = IssueDetails(issue(7, *labels), "")

    @Test fun inReviewTaskOffersRequestChangesNotUnblock() {
        val screen = PreviewScreen.initial(7, null).issueLoaded(details(Labels.IN_REVIEW, Labels.READY_FOR_CLAUDE))
        assertTrue(screen.offerRequestChanges)
        assertFalse(screen.offerUnblock)
    }

    @Test fun otherTasksDoNotOfferIt() {
        listOf(arrayOf(Labels.TODO), arrayOf(Labels.IN_PROGRESS), arrayOf(Labels.BLOCKED, Labels.NEEDS_OWNER)).forEach { labels ->
            assertFalse(labels.toList().toString(), PreviewScreen.initial(7, null).issueLoaded(details(*labels)).offerRequestChanges)
        }
        assertFalse(PreviewScreen.initial(7, null).offerRequestChanges)
    }

    @Test fun justMergedHidesIt() {
        val screen = PreviewScreen.initial(7, null).issueLoaded(details(Labels.IN_REVIEW)).mergeSucceeded()
        assertFalse(screen.offerRequestChanges)
    }

    @Test fun owedSendBackKeepsItOfferedAfterTheTaskLeftReview() {
        val screen = PreviewScreen.initial(7, null).issueLoaded(details(Labels.TODO))
            .withReply(ReplyForm(pendingMove = StatusMove.REQUEST_CHANGES))
        assertTrue(screen.offerRequestChanges)
        assertFalse(screen.offerUnblock)
    }
}
