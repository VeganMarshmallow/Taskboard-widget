package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.github.MalformedResponseException
import io.github.veganmarshmallow.taskboard.core.json.JsonWriter
import io.github.veganmarshmallow.taskboard.core.merge.PullJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PullJsonParserTest {
    private fun parse(map: Map<String, Any?>) = PullJsonParser.parsePull(JsonWriter.write(map), WIDGET)

    @Test fun fullPullRequest() {
        val pr = parse(pullJson(12, title = "מיזוג ✅", base = "main", head = "claude/30-x"))
        assertEquals(12, pr.number)
        assertEquals(WIDGET, pr.repo)
        assertEquals("מיזוג ✅", pr.title)
        assertTrue(pr.isOpen)
        assertFalse(pr.isDraft)
        assertFalse(pr.isMerged)
        assertEquals(true, pr.mergeable)
        assertEquals("main", pr.baseRef)
        assertEquals("claude/30-x", pr.headRef)
        assertEquals(SHA_A, pr.headSha)
        assertEquals("main", pr.defaultBranch)
        assertFalse(pr.isStacked)
        assertEquals("https://github.com/VeganMarshmallow/Taskboard-widget/pull/12", pr.ref.webUrl) // never the response's html_url
    }

    @Test fun mergeableNullFalseAndMissing() {
        assertNull(parse(pullJson(1, mergeable = null)).mergeable)
        assertEquals(false, parse(pullJson(1, mergeable = false)).mergeable)
        assertNull(parse(pullJson(1) - "mergeable").mergeable)
    }

    @Test fun stateDraftAndMerged() {
        assertTrue(parse(pullJson(1, draft = true)).isDraft)
        val merged = parse(pullJson(1, state = "closed", merged = true))
        assertTrue(merged.isMerged)
        assertFalse(merged.isOpen)
        assertTrue(parse(pullJson(1, state = "closed") - "merged" + ("merged_at" to "2026-10-23T10:00:00Z")).isMerged)
    }

    @Test fun stackedAndUnknownDefaultBranch() {
        assertTrue(parse(pullJson(2, base = "claude/1-first")).isStacked)
        val unknown = parse(pullJson(2, base = "claude/1-first", defaultBranch = null))
        assertNull(unknown.defaultBranch)
        assertFalse("unknown default branch is not called stacked", unknown.isStacked)
    }

    @Test fun nullOrMissingBodyAndTitleAreEmpty() {
        val pr = parse(pullJson(3, body = null) - "title")
        assertEquals("", pr.body)
        assertEquals("", pr.title)
    }

    @Test fun malformedPullRequests() {
        listOf(
            pullJson(1) - "number",
            pullJson(1) + ("number" to 0L),
            pullJson(1) + ("number" to "1"),
            pullJson(1) - "state",
            pullJson(1) - "base",
            pullJson(1) - "head",
            pullJson(1, sha = "not-a-sha"),
            pullJson(1, sha = ""),
            pullJson(1, base = ""),
        ).forEach { map -> assertThrows(MalformedResponseException::class.java) { parse(map) } }
        listOf("", "nope", "[]", "null", "\"x\"").forEach { body ->
            assertThrows(MalformedResponseException::class.java) { PullJsonParser.parsePull(body, WIDGET) }
        }
    }

    @Test fun pullList() {
        val list = PullJsonParser.parsePulls(pullsPage(pullJson(1), pullJson(2, body = "x")), WIDGET)
        assertEquals(listOf(1, 2), list.map { it.number })
        assertEquals(emptyList<Any>(), PullJsonParser.parsePulls("[]", WIDGET))
        assertThrows(MalformedResponseException::class.java) { PullJsonParser.parsePulls("[1]", WIDGET) }
        assertThrows(MalformedResponseException::class.java) { PullJsonParser.parsePulls("{}", WIDGET) }
    }

    @Test fun workflowRuns() {
        val body = runsJson(
            wfRun("CI", workflow = 7, number = 12, attempt = 2, id = 99),
            wfRun("Lint", null, "in_progress", workflow = 8, createdAt = "not a date", sha = null),
            total = 120,
        )
        val page = PullJsonParser.parseWorkflowRuns(body)
        assertEquals(120L, page.totalCount)
        val ci = page.runs[0]
        assertEquals(listOf(99L, 7L, 12L, 2L), listOf(ci.id, ci.workflowId, ci.runNumber, ci.runAttempt))
        assertEquals("CI" to SHA_A, ci.name to ci.headSha)
        assertEquals(Instant.parse("2026-10-30T10:00:00Z"), ci.createdAt)
        assertEquals("completed" to "success", ci.status to ci.conclusion)
        val lint = page.runs[1]
        assertEquals("in_progress", lint.status)
        assertNull(lint.conclusion)
        assertNull("an unreadable time is only a missing tie-breaker", lint.createdAt)
        assertNull(lint.headSha)
    }

    @Test fun workflowRunsWithMissingFields() {
        val bare = PullJsonParser.parseWorkflowRuns("""﻿{"workflow_runs":[{},{"name":"  ","run_number":"x"}]}""")
        assertNull(bare.totalCount)
        bare.runs.forEach { run ->
            assertEquals("workflow", run.name)
            assertEquals("unknown", run.status)
            assertEquals(0L to 1L, run.runNumber to run.runAttempt)
            assertNull(run.id)
            assertNull(run.workflowId)
        }
        assertNull(PullJsonParser.parseWorkflowRuns("""{"total_count":-1,"workflow_runs":[]}""").totalCount)
        assertEquals(emptyList<Any>(), PullJsonParser.parseWorkflowRuns("""{"total_count":0,"workflow_runs":[]}""").runs)
    }

    @Test fun malformedWorkflowRunsAreErrors() {
        listOf("[]", "{}", """{"workflow_runs":[1]}""", """{"workflow_runs":{}}""", """{"check_runs":[]}""", "x", "").forEach { body ->
            assertThrows(body, MalformedResponseException::class.java) { PullJsonParser.parseWorkflowRuns(body) }
        }
    }

    @Test fun messageAndMergedFlag() {
        assertEquals("Pull Request is not mergeable", PullJsonParser.message("""{"message":" Pull Request is not mergeable "}"""))
        assertNull(PullJsonParser.message("""{"message":""}"""))
        assertNull(PullJsonParser.message("<html>"))
        assertTrue(PullJsonParser.merged("""{"merged":true,"sha":"x"}"""))
        assertFalse(PullJsonParser.merged("""{"merged":false}"""))
        assertFalse(PullJsonParser.merged(""))
    }
}
