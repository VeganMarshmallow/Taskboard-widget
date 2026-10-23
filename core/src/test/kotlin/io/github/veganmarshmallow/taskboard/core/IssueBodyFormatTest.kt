package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.model.Repo
import io.github.veganmarshmallow.taskboard.core.task.IssueBodyFormat
import io.github.veganmarshmallow.taskboard.core.task.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueBodyFormatTest {
    @Test fun matchesTheIssueFormLayout() {
        val body = IssueBodyFormat.build(Repo("VeganMarshmallow", "app"), TaskType.BUG, "Fix it\n- one\n- two", "Tests pass")
        assertEquals(
            """
            ### Repo

            VeganMarshmallow/app

            ### Type

            bug

            ### What should be done

            Fix it
            - one
            - two

            ### Done when

            Tests pass

            ### Notes

            _No response_
            """.trimIndent(),
            body,
        )
    }

    @Test fun emptyFieldsReadNoResponse() {
        val body = IssueBodyFormat.build(null, TaskType.FEATURE, "", "   \n ")
        assertEquals(
            "### Repo\n\n_No response_\n\n### Type\n\nfeature\n\n### What should be done\n\n_No response_\n\n" +
                "### Done when\n\n_No response_\n\n### Notes\n\n_No response_",
            body,
        )
    }

    @Test fun everyTypeValue() {
        TaskType.entries.forEach { assertTrue(IssueBodyFormat.build(null, it, "", "").contains("### Type\n\n${it.value}\n")) }
        assertEquals(listOf("feature", "bug", "refactor", "research"), TaskType.entries.map { it.value })
    }

    @Test fun hebrewAndEmojiAreKeptAsIs() {
        val goal = "להוסיף כפתור ➕ לווידג׳ט 👩‍💻\nשורה שנייה"
        assertTrue(IssueBodyFormat.build(null, TaskType.FEATURE, goal, "").contains("\n\n$goal\n\n### Done when"))
    }

    @Test fun markdownIsKept() {
        val goal = "**bold** _it_ `code` [link](https://x.y) <b>html</b> | a | b |\n```\ncode block\n```\n> quote\n# H1\n## H2"
        assertEquals(goal, IssueBodyFormat.field(goal))
    }

    @Test fun sectionHeadingsInUserTextAreEscaped() {
        assertEquals("a\n\\### Repo\nb", IssueBodyFormat.field("a\n### Repo\nb"))
        assertEquals("\\### x", IssueBodyFormat.field("### x"))
        assertEquals("x\n  \\#### deeper", IssueBodyFormat.field("x\n  #### deeper"))
        assertEquals("text ### not at line start", IssueBodyFormat.field("text ### not at line start"))
        val body = IssueBodyFormat.build(null, TaskType.FEATURE, "### Done when\nsneaky", "")
        assertEquals(1, Regex("^### Done when$", RegexOption.MULTILINE).findAll(body).count())
    }

    @Test fun lineEndingsAreNormalizedAndOuterWhitespaceTrimmed() {
        assertEquals("a\nb\nc", IssueBodyFormat.field("\r\n  a\r\nb\rc  \n"))
        assertFalse(IssueBodyFormat.build(null, TaskType.BUG, "x\r\ny", "").contains('\r'))
    }

    @Test fun veryLongTextIsKeptWhole() {
        val goal = "א".repeat(50_000)
        assertTrue(IssueBodyFormat.build(null, TaskType.FEATURE, goal, "").contains(goal))
    }
}
