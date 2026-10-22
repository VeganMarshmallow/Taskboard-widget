package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.github.IssueJsonParser
import io.github.veganmarshmallow.taskboard.core.github.MalformedResponseException
import io.github.veganmarshmallow.taskboard.core.json.JsonParseException
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Reviewer-added edge cases for unexpected JSON shapes and types. */
class ParsingEdgeCasesTest {
    private fun one(json: String) = IssueJsonParser.parsePage("[$json]").single()

    @Test fun labelsThatAreNotAnArrayAreIgnored() {
        listOf("\"bug\"", "null", "{}", "42", "true").forEach { labels ->
            assertEquals("labels=$labels", emptySet<String>(), one("""{"number":2,"state":"open","labels":$labels}""").labels)
        }
    }

    @Test fun labelObjectsWithNonStringNamesAreSkipped() {
        val labels = one("""{"number":2,"state":"open","labels":[{"name":7},{"name":null},{"name":["x"]},{"name":"status: todo"}]}""").labels
        assertEquals(setOf("status: todo"), labels)
    }

    @Test fun duplicateLabelsCollapse() {
        assertEquals(setOf("status: todo"), one("""{"number":2,"state":"open","labels":["status: todo","Status: Todo",{"name":"STATUS: TODO"}]}""").labels)
    }

    @Test fun nullOrNonStringTitleBecomesEmpty() {
        listOf("null", "42", "[]", "{}", "false").forEach { title ->
            assertEquals("", one("""{"number":2,"state":"open","title":$title}""").title)
        }
    }

    @Test fun integralDoubleNumbersAreAccepted() {
        assertEquals(5, one("""{"number":5.0,"state":"open"}""").number)
        assertEquals(5, one("""{"number":5e0,"state":"open"}""").number)
    }

    @Test fun outOfRangeOrNonNumericNumbersAreRejected() {
        listOf("2147483648", "1e20", "null", "true", "[5]").forEach { n ->
            assertThrows("number=$n", MalformedResponseException::class.java) {
                IssueJsonParser.parsePage("""[{"number":$n,"state":"open"}]""")
            }
        }
        assertEquals(Int.MAX_VALUE, one("""{"number":2147483647,"state":"open"}""").number)
    }

    @Test fun stateIsCaseInsensitiveAndUnknownStatesAreClosed() {
        assertTrue(one("""{"number":2,"state":"OPEN"}""").isOpen)
        assertFalse(one("""{"number":2,"state":"locked"}""").isOpen)
        assertThrows(MalformedResponseException::class.java) { IssueJsonParser.parsePage("""[{"number":2,"state":null}]""") }
        assertThrows(MalformedResponseException::class.java) { IssueJsonParser.parsePage("""[{"number":2,"state":1}]""") }
    }

    @Test fun closedAtAcceptsOffsetsAndFractionsAndNull() {
        assertEquals(Instant.parse("2026-10-20T05:30:00Z"), one("""{"number":2,"state":"closed","closed_at":"2026-10-20T08:30:00+03:00"}""").closedAt)
        assertEquals(Instant.parse("2026-10-20T08:30:00.123Z"), one("""{"number":2,"state":"closed","closed_at":"2026-10-20T08:30:00.123Z"}""").closedAt)
        assertNull(one("""{"number":2,"state":"closed","closed_at":null}""").closedAt)
        assertNull(one("""{"number":2,"state":"closed","closed_at":17}""").closedAt) // not a string: ignored
    }

    @Test fun closedAtWithoutZoneOrDateOnlyIsRejected() {
        listOf("2026-10-20T08:30:00", "2026-10-20", "", "2026-13-01T00:00:00Z").forEach { at ->
            assertThrows("closed_at=$at", MalformedResponseException::class.java) {
                IssueJsonParser.parsePage("""[{"number":2,"state":"closed","closed_at":"$at"}]""")
            }
        }
    }

    @Test fun pullRequestMustBeAnObject() {
        assertTrue(one("""{"number":2,"state":"open","pull_request":{}}""").isPullRequest)
        listOf("false", "0", "\"\"", "null").forEach {
            assertFalse("pull_request=$it", one("""{"number":2,"state":"open","pull_request":$it}""").isPullRequest)
        }
    }

    @Test fun extraUnknownFieldsAreIgnored() {
        val body = """[{"number":2,"state":"open","title":"t","reactions":{"+1":3},"body":null,"milestone":{"x":[1,2,{"y":null}]}}]"""
        assertEquals("t", IssueJsonParser.parsePage(body).single().title)
    }

    @Test fun byteOrderMarkIsTolerated() {
        assertEquals(2, IssueJsonParser.parsePage("\uFEFF[{\"number\":2,\"state\":\"open\"}]").single().number)
    }

    @Test fun nullElementsInTheArrayAreRejected() {
        assertThrows(MalformedResponseException::class.java) { IssueJsonParser.parsePage("""[null]""") }
        assertThrows(MalformedResponseException::class.java) { IssueJsonParser.parsePage("""[{"number":2,"state":"open"},"x"]""") }
    }

    @Test fun unicodeEscapesNeedFourHexDigits() {
        // Previously "\u+041" and "\u-041" slipped through String.toIntOrNull(16), which accepts a sign.
        listOf("\"\\u+041\"", "\"\\u-041\"", "\"\\u 041\"", "\"\\u004\"", "\"\\uZZZZ\"", "\"\\u٠٠٤١\"").forEach { bad ->
            assertThrows("should reject $bad", JsonParseException::class.java) { JsonParser.parse(bad) }
        }
        assertEquals("A\u00e9\u05D0", JsonParser.parse("\"\\u0041\\u00E9\\u05d0\""))
    }

    @Test fun loneSurrogateEscapesParseAndAreCleanedLater() {
        val issue = one("""{"number":2,"state":"open","title":"bad \ud800 pair"}""")
        assertEquals("bad \uD800 pair", issue.title)
        assertEquals("bad \uFFFD pair", io.github.veganmarshmallow.taskboard.core.board.TitleCleaner.clean(issue.title))
    }

    @Test fun hebrewPageWithManyIssuesParses() {
        val many = (2..101).map { issueJson(it, "status: todo", title = "משימה מספר $it ✅") }.toTypedArray()
        val parsed = IssueJsonParser.parsePage(page(*many))
        assertEquals(100, parsed.size)
        assertEquals("משימה מספר 101 ✅", parsed.last().title)
    }

    @Test fun duplicateKeysLastOneWins() {
        assertEquals(3, one("""{"number":2,"number":3,"state":"open"}""").number)
    }

    @Test fun hugeBodyOfJunkIsRejectedNotCrashed() {
        assertThrows(MalformedResponseException::class.java) { IssueJsonParser.parsePage("[" + "1,".repeat(200_000) + "1]") }
        assertThrows(MalformedResponseException::class.java) { IssueJsonParser.parsePage("[".repeat(10_000)) }
    }
}
