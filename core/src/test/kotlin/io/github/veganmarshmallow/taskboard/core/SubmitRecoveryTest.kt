package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.json.JsonWriter
import io.github.veganmarshmallow.taskboard.core.task.IssueResponseParser
import io.github.veganmarshmallow.taskboard.core.task.RecentIssue
import io.github.veganmarshmallow.taskboard.core.task.SubmitMarker
import io.github.veganmarshmallow.taskboard.core.task.SubmitRecovery
import io.github.veganmarshmallow.taskboard.core.task.SubmitRecovery.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant

class SubmitRecoveryTest {
    private val started: Instant = NOW.minusSeconds(120)
    private val marker = SubmitMarker("Fix the widget", started)
    private fun issue(number: Int, title: String = marker.title, at: Instant? = started.plusSeconds(2), pr: Boolean = false) =
        RecentIssue(number, title, at, pr)

    @Test fun matchesExactTitleCreatedInTheWindow() {
        assertEquals(12, SubmitRecovery.match(marker, listOf(issue(11, "Other"), issue(12))))
    }

    @Test fun noMatchForOtherTitlesCaseOrPullRequests() {
        val issues = listOf(issue(1, "fix the widget"), issue(2, "Fix the widget!"), issue(3, pr = true), issue(4, ""))
        assertNull(SubmitRecovery.match(marker, issues))
        assertNull(SubmitRecovery.match(marker, emptyList()))
    }

    @Test fun surroundingSpacesAreIgnored() {
        assertEquals(5, SubmitRecovery.match(marker, listOf(issue(5, "  Fix the widget "))))
        assertEquals(5, SubmitRecovery.match(SubmitMarker(" Fix the widget", started), listOf(issue(5))))
    }

    @Test fun windowBoundariesAreInclusive() {
        val first = started.minus(SubmitRecovery.LOOK_BEFORE)
        val last = started.plus(SubmitRecovery.LOOK_AFTER)
        assertEquals(1, SubmitRecovery.match(marker, listOf(issue(1, at = first))))
        assertEquals(2, SubmitRecovery.match(marker, listOf(issue(2, at = last))))
        assertNull(SubmitRecovery.match(marker, listOf(issue(3, at = first.minusMillis(1)))))
        assertNull(SubmitRecovery.match(marker, listOf(issue(4, at = last.plusMillis(1)))))
    }

    @Test fun anOlderIssueWithTheSameTitleIsNotThisSubmit() {
        assertNull(SubmitRecovery.match(marker, listOf(issue(9, at = started.minus(Duration.ofDays(3))))))
    }

    @Test fun unknownCreationTimeNeverMatches() {
        assertNull(SubmitRecovery.match(marker, listOf(issue(7, at = null))))
    }

    @Test fun severalMatchesPickTheFirstCreated() {
        assertEquals(20, SubmitRecovery.match(marker, listOf(issue(22), issue(20), issue(21))))
    }

    @Test fun decideCoversEveryOutcome() {
        assertEquals(Result.Created(marker.title, 12), SubmitRecovery.decide(marker, Outcome.Success(listOf(issue(12)))))
        assertEquals(Result.NotCreated(marker.title), SubmitRecovery.decide(marker, Outcome.Success(listOf(issue(12, "x")))))
        assertEquals(Result.NotCreated(marker.title), SubmitRecovery.decide(marker, Outcome.Success(emptyList())))
        assertEquals(
            Result.CheckFailed(marker.title, BoardError.Network),
            SubmitRecovery.decide(marker, Outcome.Failure(BoardError.Network)),
        )
    }

    @Test fun checkLooksUpOnceFromBeforeTheMarker() {
        val asked = mutableListOf<Instant>()
        val result = SubmitRecovery.check(marker, NOW) { asked += it; Outcome.Success(listOf(issue(3))) }
        assertEquals(Result.Created(marker.title, 3), result)
        assertEquals(listOf(started.minus(SubmitRecovery.LOOK_BEFORE)), asked)
    }

    @Test fun tooOldMarkerIsNotLookedUp() {
        val old = SubmitMarker("Old", NOW.minus(SubmitRecovery.MAX_AGE).minusSeconds(1))
        var calls = 0
        assertEquals(Result.TooOld("Old"), SubmitRecovery.check(old, NOW) { calls++; Outcome.Success(emptyList()) })
        assertEquals(0, calls)
        assertFalse(SubmitRecovery.isTooOld(SubmitMarker("x", NOW.minus(SubmitRecovery.MAX_AGE)), NOW))
    }

    @Test fun markerFromTheFutureIsStillChecked() {
        assertFalse(SubmitRecovery.isTooOld(SubmitMarker("x", NOW.plus(Duration.ofDays(5))), NOW))
    }

    @Test fun apiSendsOneGetOfAllIssuesSinceAndParsesThem() {
        val body = JsonWriter.write(
            listOf(
                mapOf("number" to 31L, "title" to "Fix the widget", "created_at" to "2026-10-22T11:58:03Z"),
                mapOf("number" to 30L, "title" to "PR", "created_at" to "2026-10-22T11:58:00Z", "pull_request" to mapOf("url" to "u")),
            ),
        )
        val http = FakeHttp().on({ it.method == "GET" }) { ok(body) }
        val api = GitHubApi(http, TOKEN, FIXED_CLOCK)
        val result = SubmitRecovery.check(marker, NOW, api::issuesUpdatedSince)
        assertEquals(Result.Created(marker.title, 31), result)
        val url = http.requests.single().url
        assertTrue(url, url.startsWith("https://api.github.com/repos/VeganMarshmallow/claude-workspace/issues?"))
        listOf("state=all", "since=2026-10-22T11%3A53%3A00Z", "sort=created", "direction=desc", "per_page=100").forEach {
            assertTrue("$it in $url", it in url)
        }
    }

    @Test fun apiFailureIsACheckFailure() {
        val api = GitHubApi(FakeHttp().on({ true }) { throw IOException("down") }, TOKEN, FIXED_CLOCK)
        assertEquals(Result.CheckFailed(marker.title, BoardError.Network), SubmitRecovery.check(marker, NOW, api::issuesUpdatedSince))
        val unauthorized = GitHubApi(FakeHttp().on({ true }) { status(401) }, TOKEN, FIXED_CLOCK)
        assertTrue(SubmitRecovery.check(marker, NOW, unauthorized::issuesUpdatedSince) is Result.CheckFailed)
    }

    @Test fun parserSkipsBrokenEntriesAndKeepsUnreadableDates() {
        val body = """[{"number":1,"title":"a","created_at":"nope"},{"number":0,"title":"b"},{"title":"c"},
            {"number":2},"x",{"number":3,"title":"d","created_at":"2026-10-22T13:58:03+02:00"}]"""
        assertEquals(
            listOf(RecentIssue(1, "a", null), RecentIssue(3, "d", Instant.parse("2026-10-22T11:58:03Z"))),
            IssueResponseParser.recentIssues(body),
        )
        listOf("", "not json", "{}", "null").forEach { assertEquals(emptyList<RecentIssue>(), IssueResponseParser.recentIssues(it)) }
    }
}
