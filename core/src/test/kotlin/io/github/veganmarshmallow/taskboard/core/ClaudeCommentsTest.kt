package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.preview.ClaudeComments
import io.github.veganmarshmallow.taskboard.core.preview.IssueComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ClaudeCommentsTest {
    private var nextId = 1L
    private fun c(body: String, at: String? = null) = IssueComment(nextId++, body, at?.let(Instant::parse))

    @Test fun recognisesTheThreeMarkers() {
        listOf("🤖 Started", "✅ Done", "⛔ Blocked", "✅️ with VS16", "  \n🤖 indented", "\uFEFF🤖 bom", "\u200B✅ zwsp").forEach {
            assertTrue(it, ClaudeComments.isFromClaude(it))
        }
    }

    @Test fun ignoresEverythingElse() {
        listOf("", " ", "Thanks!", "I think 🤖 is cool", "> 🤖 quoted", "`🤖`", "✔ heavy check", "❌ cross", "🚫 no entry", "👍").forEach {
            assertFalse(it, ClaudeComments.isFromClaude(it))
        }
    }

    @Test fun latestByListOrderWhenTimesAreEqualOrMissing() {
        val comments = listOf(c("🤖 Started"), c("owner reply"), c("⛔ Blocked: need a token"), c("ok will do"))
        assertEquals("⛔ Blocked: need a token", ClaudeComments.latest(comments)!!.body)
    }

    @Test fun latestByCreatedAtEvenIfListIsOutOfOrder() {
        val comments = listOf(
            c("✅ Finished", "2026-10-22T10:00:00Z"),
            c("🤖 Started", "2026-10-21T10:00:00Z"),
        )
        assertEquals("✅ Finished", ClaudeComments.latest(comments)!!.body)
    }

    @Test fun commentsWithoutTimeRankBelowDatedOnes() {
        val comments = listOf(c("🤖 dated", "2026-10-21T10:00:00Z"), c("🤖 undated"))
        assertEquals("🤖 dated", ClaudeComments.latest(comments)!!.body)
    }

    @Test fun noneOrNoClaudeComments() {
        assertNull(ClaudeComments.latest(emptyList()))
        assertNull(ClaudeComments.latestMessage(listOf(c("hi"), c("there"))))
    }

    @Test fun messageIsPlainTextAndKeepsTheMarker() {
        val body = "⛔ **Blocked**: the repo `owner/x` doesn't exist.\n\n- Create it\n- Or give [access](https://github.com/settings)"
        assertEquals("⛔ Blocked: the repo owner/x doesn't exist.\n• Create it\n• Or give access", body(c(body)))
    }

    @Test fun messageDropsInvisiblePrefixBeforeTheMarker() {
        assertEquals("⛔ Blocked: why", body(c("\u200B⛔ **Blocked**: why")))
        assertEquals("🤖 hi", body(c("\uFEFF\u2060 🤖 hi")))
    }

    @Test fun messageIsNotCappedToALine() {
        val text = body(c("🤖 " + "word ".repeat(200)))!!
        assertEquals("🤖 " + "word ".repeat(200).trim(), text)
    }

    @Test fun markerOnlyCommentStillShows() {
        assertEquals("✅", body(c("✅")))
    }

    @Test fun hebrewComment() {
        assertEquals("🤖 התחלתי לעבוד:\nתוכנית קצרה", body(c("🤖 התחלתי לעבוד:\n\n**תוכנית** קצרה")))
    }

    @Test fun manyComments() {
        val comments = (1..5_000).map { c(if (it % 7 == 0) "🤖 run $it" else "note $it") }
        assertEquals("🤖 run 4998", ClaudeComments.latestMessage(comments)?.body)
    }

    @Test fun commentStartingWithAFenceIsNotClaudes() {
        // The marker is inside the code block, so the comment doesn't start with it.
        assertNull(ClaudeComments.latestMessage(listOf(c("```\n🤖 inside code\n```"))))
    }

    private fun body(comment: IssueComment): String? = ClaudeComments.latestMessage(listOf(comment))?.body
}
