package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.merge.ClosesReference
import io.github.veganmarshmallow.taskboard.core.merge.TaskMatch
import io.github.veganmarshmallow.taskboard.core.model.Repo
import org.junit.Assert.assertEquals
import org.junit.Test

class ClosesReferenceTest {
    private fun match(body: String, issue: Int = 30) = ClosesReference.match(body, Repo.BOARD, issue)

    @Test fun claudesLineIsExact() {
        assertEquals(TaskMatch.EXACT, match("Closes VeganMarshmallow/claude-workspace#30\n\nWhat changed"))
        assertEquals(TaskMatch.EXACT, match("closes veganmarshmallow/Claude-Workspace#30"))
        assertEquals(TaskMatch.EXACT, match("Closed: VeganMarshmallow/claude-workspace#30."))
        assertEquals(TaskMatch.EXACT, match("שינויים\nCloses VeganMarshmallow/claude-workspace#30 ✅"))
    }

    @Test fun exactOnlyAtTheStartOfALine() {
        assertEquals(TaskMatch.MENTION, match("This doesn't close VeganMarshmallow/claude-workspace#30 yet"))
        assertEquals(TaskMatch.MENTION, match("Does not Closes VeganMarshmallow/claude-workspace#30"))
        assertEquals(TaskMatch.EXACT, match("- Closes VeganMarshmallow/claude-workspace#30"))
        assertEquals(TaskMatch.EXACT, match("**Closes** VeganMarshmallow/claude-workspace#30"))
        assertEquals(TaskMatch.EXACT, match("> Closes VeganMarshmallow/claude-workspace#30"))
        assertEquals(TaskMatch.EXACT, match("Intro\r\n  Closes VeganMarshmallow/claude-workspace#30\r\n"))
        assertEquals(TaskMatch.NONE, match("Closes evilVeganMarshmallow/claude-workspace#30"))
        // Past the 64 KB input cap: ignored rather than scanned.
        assertEquals(TaskMatch.NONE, match("x".repeat(70_000) + "\nCloses VeganMarshmallow/claude-workspace#30"))
    }

    @Test fun numbersMustMatchExactly() {
        assertEquals(TaskMatch.NONE, match("Closes VeganMarshmallow/claude-workspace#300"))
        assertEquals(TaskMatch.NONE, match("Closes VeganMarshmallow/claude-workspace#3"))
        assertEquals(TaskMatch.EXACT, match("Closes VeganMarshmallow/claude-workspace#3", issue = 3))
    }

    @Test fun otherReferencesAreMentions() {
        assertEquals(TaskMatch.MENTION, match("Fixes VeganMarshmallow/claude-workspace#30"))
        assertEquals(TaskMatch.MENTION, match("Part of VeganMarshmallow/claude-workspace#30"))
        assertEquals(TaskMatch.MENTION, match("https://github.com/VeganMarshmallow/claude-workspace/issues/30"))
    }

    @Test fun otherReposAndShortFormsDoNotCount() {
        assertEquals(TaskMatch.NONE, match("Closes someone/claude-workspace#30"))
        assertEquals(TaskMatch.NONE, match("Closes VeganMarshmallow/claude-workspace-old#30"))
        assertEquals(TaskMatch.NONE, match("Closes #30"))
        assertEquals(TaskMatch.NONE, match(""))
    }
}
