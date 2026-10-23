package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.model.Priority
import io.github.veganmarshmallow.taskboard.core.model.Repo
import io.github.veganmarshmallow.taskboard.core.task.CreateErrors
import io.github.veganmarshmallow.taskboard.core.task.InvisibleText
import io.github.veganmarshmallow.taskboard.core.task.IssueBodyFormat
import io.github.veganmarshmallow.taskboard.core.task.NewIssue
import io.github.veganmarshmallow.taskboard.core.task.RepoInput
import io.github.veganmarshmallow.taskboard.core.task.RepoSuggestions
import io.github.veganmarshmallow.taskboard.core.task.TaskDraft
import io.github.veganmarshmallow.taskboard.core.task.TaskDraftValidator
import io.github.veganmarshmallow.taskboard.core.task.TaskDraftValidator.Field
import io.github.veganmarshmallow.taskboard.core.task.TaskLabels
import io.github.veganmarshmallow.taskboard.core.task.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reviewer edge cases for the New task form (#9): board compatibility, RTL input, pasted links, ambiguous failures. */
class TaskFormEdgeCasesTest {
    private val rlm = "‏"
    private val lrm = "‎"

    private fun valid(input: String) = (RepoInput.parse(input) as RepoInput.Result.Valid).repo
    private fun invalid(input: String) = (RepoInput.parse(input) as RepoInput.Result.Invalid).reason
    private fun validate(d: TaskDraft) = TaskDraftValidator.validate(d)

    // --- The body and labels the scheduled board runs read ---

    /** Section headings, order and blank lines exactly as `.github/ISSUE_TEMPLATE/task.yml` renders them. */
    @Test fun bodyHeadingsMatchTheIssueFormInOrder() {
        val body = IssueBodyFormat.build(Repo("o", "r"), TaskType.FEATURE, "g", "d")
        val headings = Regex("^### (.+)$", RegexOption.MULTILINE).findAll(body).map { it.groupValues[1] }.toList()
        assertEquals(listOf("Repo", "Type", "What should be done", "Done when", "Notes"), headings)
        assertFalse(body.startsWith("\n"))
        assertFalse(body.endsWith("\n"))
        assertFalse(body.contains("\n\n\n"))
    }

    @Test fun bodyRepoIsReadBackByTheSuggestionParser() {
        val body = (validate(TaskDraft(title = "t", repo = "  my-app ")) as TaskDraftValidator.Result.Valid).issue.body
        assertEquals(Repo("VeganMarshmallow", "my-app"), RepoSuggestions.repoIn(body))
        val noRepo = (validate(TaskDraft(title = "t")) as TaskDraftValidator.Result.Valid).issue.body
        assertNull(RepoSuggestions.repoIn(noRepo))
    }

    /** Every label the form can send exists on the board, spelled exactly (labels list of claude-workspace). */
    @Test fun labelsAreExactlyTheBoardsLabelNames() {
        val board = setOf(
            "status: todo", "ready-for-claude", "priority: high", "priority: low",
            "type: feature", "type: bug", "type: refactor", "type: research",
        )
        for (type in TaskType.entries) for (p in Priority.entries) for (approve in listOf(true, false)) {
            val labels = TaskLabels.of(type, p, approve)
            assertTrue(labels.toString(), board.containsAll(labels))
            assertEquals(Labels.TODO, labels.first())
            assertEquals(approve, Labels.READY_FOR_CLAUDE in labels)
            assertEquals(labels.size, labels.toSet().size)
            assertFalse(labels.any { it.contains("%") || it != it.trim() })
        }
    }

    @Test fun approveNowUncheckedNeverAddsReadyForClaude() {
        val issue = (validate(TaskDraft(title = "t", approveNow = false)) as TaskDraftValidator.Result.Valid).issue
        assertFalse(Labels.READY_FOR_CLAUDE in issue.labels)
        assertFalse(issue.labels.contains("created-by-claude"))
    }

    // --- Hebrew, RTL and invisible characters ---

    @Test fun titleOfOnlyBidiMarksIsRequired() {
        listOf(rlm, "$rlm $lrm", "​", "﻿", "⁧⁩", " ").forEach {
            assertEquals(it, setOf(Field.TITLE), (validate(TaskDraft(title = it)) as TaskDraftValidator.Result.Invalid).errors.keys)
        }
    }

    @Test fun bidiMarksInsideARealTitleAreKept() {
        val title = "${rlm}לתקן את v2.0$lrm בווידג׳ט"
        assertEquals(title, (validate(TaskDraft(title = title)) as TaskDraftValidator.Result.Valid).issue.title)
    }

    @Test fun fieldOfOnlyBidiMarksIsNoResponse() {
        assertEquals(IssueBodyFormat.NO_RESPONSE, IssueBodyFormat.field("$rlm\n $lrm "))
        assertEquals("${rlm}שלום", IssueBodyFormat.field("${rlm}שלום"))
    }

    @Test fun repoWithInvisibleMarksFromAHebrewKeyboardParses() {
        assertEquals(Repo("VeganMarshmallow", "app"), valid("${rlm}VeganMarshmallow/app$lrm"))
        assertEquals(Repo("o", "r"), valid("‫o/​r‬"))
        assertEquals(RepoInput.Result.Empty, RepoInput.parse("$rlm $lrm"))
    }

    @Test fun mixedRtlBodyRoundTripsThroughJson() {
        val goal = "להוסיף ${lrm}README.md$lrm ו-👩‍💻 emoji\n- פריט \"מצוטט\" \\ backslash\ttab\u0001ctl"
        val issue = NewIssue("כותרת 🎉", IssueBodyFormat.build(null, TaskType.FEATURE, goal, ""), listOf(Labels.TODO))
        val http = FakeHttp().on({ true }) { HttpResponse(201, body = """{"number":1}""") }
        GitHubApi(http, TOKEN, FIXED_CLOCK).createIssue(issue)
        val json = JsonParser.parse(http.requests.single().body!!) as Map<*, *>
        assertEquals(issue.title, json["title"])
        assertEquals(issue.body, json["body"])
    }

    // --- Markdown that could fake or hide a section ---

    @Test fun headingInjectionVariantsAreEscaped() {
        listOf("### Repo", "   ### Repo", "###Repo", "#### Repo", "### Done when\n\nfake").forEach {
            val body = IssueBodyFormat.build(null, TaskType.BUG, it, "")
            assertEquals(it, 5, Regex("^ {0,3}###", RegexOption.MULTILINE).findAll(body).count())
        }
        // Not headings in markdown: left as typed.
        assertEquals("    ### code", IssueBodyFormat.field("x\n    ### code").substringAfter("x\n"))
        assertEquals("> ### quoted", IssueBodyFormat.field("> ### quoted"))
    }

    @Test fun noResponseTypedByTheUserIsJustText() {
        assertEquals("_No response_", IssueBodyFormat.field("_No response_"))
    }

    // --- Repo field: links, whitespace, invalid characters ---

    @Test fun pastedLinksOfAnyRepoPage() {
        listOf(
            "https://github.com/o/r/issues/3", "https://github.com/o/r/tree/main/src", "https://github.com/o/r?tab=readme",
            "https://github.com/o/r#readme", "https://github.com/o/r.git/", "git@github.com:o/r.git", "GITHUB.COM/o/r",
            "https://www.github.com//o//r",
        ).forEach { assertEquals(it, Repo("o", "r"), valid(it)) }
    }

    /** A link to a user or org page has no repo: it must not become VeganMarshmallow/<owner>. */
    @Test fun linkWithoutARepoNameIsInvalid() {
        assertTrue(invalid("https://github.com/someone").startsWith("Repo name is missing"))
        assertTrue(invalid("https://github.com/someone/").startsWith("Repo name is missing"))
        assertTrue(invalid("https://github.com/").startsWith("Owner is missing"))
        assertTrue(invalid("https://github.com/?tab=x").startsWith("Owner is missing"))
    }

    @Test fun otherHostsAreNotLinks() {
        assertTrue(invalid("https://gitlab.com/o/r").isNotEmpty())
        assertTrue(invalid("https://evilgithub.com/o/r").isNotEmpty())
    }

    @Test fun spacesAroundTheSlashAreForgiven() {
        assertEquals(Repo("VeganMarshmallow", "app"), valid(" VeganMarshmallow / app "))
        assertEquals(Repo("o", "r"), valid("o/\tr"))
        assertTrue(invalid("o/my app").contains("valid repo name"))
    }

    @Test fun invalidCharacters() {
        listOf("o/r;rm -rf", "o/<script>", "o/r\nx", "o/r%20x", "o/r@x", "o/../r").forEach {
            assertTrue(it, RepoInput.parse(it) is RepoInput.Result.Invalid)
        }
    }

    @Test fun strictParsingOfLinksInIssueBodies() {
        assertEquals(Repo("o", "r"), RepoInput.parseStrict("https://github.com/o/r/pull/2"))
        assertEquals(Repo("o", "r"), RepoInput.parseStrict("$rlm o/r"))
        assertNull(RepoInput.parseStrict("https://github.com/o"))
        assertEquals(Repo("o", "r"), RepoSuggestions.repoIn("Repo: https://github.com/o/r."))
    }

    // --- Failures: which ones may have created the issue anyway ---

    @Test fun onlyAmbiguousFailuresWarnTheIssueMayExist() {
        assertTrue(CreateErrors.mayAlreadyExist(BoardError.Network))
        assertTrue("a create that timed out may well have gone through", CreateErrors.mayAlreadyExist(BoardError.Timeout))
        assertTrue(CreateErrors.mayAlreadyExist(BoardError.Server(502)))
        listOf(
            BoardError.NoToken, BoardError.BadToken(401), BoardError.BadToken(403), BoardError.NoAccess,
            BoardError.RateLimited(null), BoardError.Unexpected("HTTP 422: x"),
        ).forEach { assertFalse(it.toString(), CreateErrors.mayAlreadyExist(it)) }
    }

    @Test fun every4xxOnCreateIsClassified() {
        val issue = NewIssue("t", "b", listOf(Labels.TODO))
        fun err(status: Int, body: String = "{}") =
            (GitHubApi(FakeHttp().on({ true }) { HttpResponse(status, body = body) }, TOKEN, FIXED_CLOCK).createIssue(issue) as Outcome.Failure).error
        assertEquals(BoardError.BadToken(401), err(401, """{"message":"Bad credentials"}"""))
        assertEquals(BoardError.BadToken(403), err(403, """{"message":"Resource not accessible by personal access token"}"""))
        assertEquals(BoardError.NoAccess, err(404, """{"message":"Not Found"}"""))
        assertEquals(BoardError.Unexpected("HTTP 422: Validation Failed: body is too long (maximum is 65536 characters)"),
            err(422, """{"message":"Validation Failed","errors":[{"message":"body is too long (maximum is 65536 characters)"}]}"""))
        assertEquals(BoardError.Unexpected("HTTP 422: GitHub rejected the task"), err(422, ""))
        assertEquals(BoardError.Unexpected("HTTP 400"), err(400))
        assertEquals(BoardError.Unexpected("HTTP 301"), err(301))
    }

    // --- Suggestions: empty and failing ---

    @Test fun suggestionsFromEmptyOrFailingListings() {
        fun suggestions(r: HttpResponse) = GitHubApi(FakeHttp().on({ true }) { r }, TOKEN, FIXED_CLOCK).repoSuggestions()
        assertEquals(emptyList<String>(), (suggestions(HttpResponse(200, body = "[]")) as Outcome.Success).value)
        assertEquals(emptyList<String>(), (suggestions(HttpResponse(200, body = "")) as Outcome.Success).value)
        assertEquals(emptyList<String>(), (suggestions(HttpResponse(200, body = """[{"body":"no repo here"},{"body":5},1]""")) as Outcome.Success).value)
        assertEquals(BoardError.Server(500), (suggestions(HttpResponse(500)) as Outcome.Failure).error)
        val down = GitHubApi(FakeHttp().on({ true }) { throw java.io.IOException("offline") }, TOKEN, FIXED_CLOCK).repoSuggestions()
        assertEquals(BoardError.Network, (down as Outcome.Failure).error)
    }

    @Test fun invisibleHelpers() {
        assertTrue(InvisibleText.isBlank(""))
        assertTrue(InvisibleText.isBlank(" $rlm\n"))
        assertFalse(InvisibleText.isBlank("א"))
        assertEquals("ab", InvisibleText.strip("a‍b"))
    }
}
