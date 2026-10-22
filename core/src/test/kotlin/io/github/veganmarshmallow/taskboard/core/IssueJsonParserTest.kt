package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.github.IssueJsonParser
import io.github.veganmarshmallow.taskboard.core.github.MalformedResponseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class IssueJsonParserTest {
    @Test fun parsesIssueFields() {
        val issues = IssueJsonParser.parsePage(
            page(
                issueJson(6, "status: todo", "Priority: High", title = "Build widget"),
                issueJson(9, "status: done", state = "closed", closedAt = "2026-10-20T08:30:00Z"),
            ),
        )
        assertEquals(2, issues.size)
        with(issues[0]) {
            assertEquals(6, number)
            assertEquals("Build widget", title)
            assertEquals(setOf("status: todo", "priority: high"), labels) // normalized to lower case
            assertTrue(isOpen)
            assertNull(closedAt)
            assertFalse(isPullRequest)
        }
        assertFalse(issues[1].isOpen)
        assertEquals(Instant.parse("2026-10-20T08:30:00Z"), issues[1].closedAt)
    }

    @Test fun flagsPullRequests() {
        assertTrue(IssueJsonParser.parsePage(page(issueJson(3, pr = true)))[0].isPullRequest)
    }

    @Test fun pullRequestKeyWithNullValueIsNotAPr() {
        val body = """[{"number":3,"title":"t","state":"open","labels":[],"pull_request":null}]"""
        assertFalse(IssueJsonParser.parsePage(body)[0].isPullRequest)
    }

    @Test fun emptyArrayIsEmptyList() {
        assertEquals(emptyList<Any>(), IssueJsonParser.parsePage("[]"))
        assertEquals(emptyList<Any>(), IssueJsonParser.parsePage(" [ ] \n"))
    }

    @Test fun toleratesMissingOptionalFields() {
        val issues = IssueJsonParser.parsePage("""[{"number":4,"state":"open"}]""")
        assertEquals("", issues[0].title)
        assertEquals(emptySet<String>(), issues[0].labels)
    }

    @Test fun toleratesOddLabelShapes() {
        val body = """[{"number":4,"state":"open","title":"t","labels":["Needs-Owner",{"name":"x"},{"id":1},42,null,{"name":"  "}]}]"""
        assertEquals(setOf("needs-owner", "x"), IssueJsonParser.parsePage(body)[0].labels)
    }

    @Test fun unicodeAndEmojiTitlesSurvive() {
        val title = "📋 Board — בדיקה ✅ 日本語 👩‍💻"
        assertEquals(title, IssueJsonParser.parsePage(page(issueJson(2, title = title)))[0].title)
    }

    @Test fun rejectsMalformedBodies() {
        listOf(
            "", "not json", "{\"message\":\"Bad credentials\"}", "null", "[1,2]", "[[]]",
            """[{"title":"no number","state":"open"}]""",
            """[{"number":"5","state":"open"}]""",
            """[{"number":0,"state":"open"}]""",
            """[{"number":-3,"state":"open"}]""",
            """[{"number":1.5,"state":"open"}]""",
            """[{"number":5}]""",
            """[{"number":5,"state":"closed","closed_at":"yesterday"}]""",
            "[{\"number\":5,\"state\":\"open\"}", // truncated
        ).forEach { bad ->
            assertThrows("should reject: $bad", MalformedResponseException::class.java) { IssueJsonParser.parsePage(bad) }
        }
    }

    @Test fun largePageParses() {
        val many = (1..100).map { issueJson(it, "status: todo") }.toTypedArray()
        assertEquals(100, IssueJsonParser.parsePage(page(*many)).size)
    }
}
