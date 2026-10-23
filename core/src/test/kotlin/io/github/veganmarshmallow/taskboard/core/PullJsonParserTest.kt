package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.github.MalformedResponseException
import io.github.veganmarshmallow.taskboard.core.json.JsonWriter
import io.github.veganmarshmallow.taskboard.core.merge.CheckRun
import io.github.veganmarshmallow.taskboard.core.merge.PullJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test fun checkRuns() {
        val page = PullJsonParser.parseCheckRuns(checkRunsJson(passed("build"), Triple("emulator", "in_progress", null)))
        assertEquals(listOf(CheckRun("build", "completed", "success"), CheckRun("emulator", "in_progress", null)), page.runs)
        assertEquals(2, page.totalCount)
        val unnamed = PullJsonParser.parseCheckRuns("""{"check_runs":[{}]}""")
        assertEquals(CheckRun("check", "unknown", null), unnamed.runs.single())
        assertNull(unnamed.totalCount)
        listOf("[]", "{}", """{"check_runs":[1]}""", "x").forEach { body ->
            assertThrows(MalformedResponseException::class.java) { PullJsonParser.parseCheckRuns(body) }
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
