package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.model.Priority
import io.github.veganmarshmallow.taskboard.core.task.TaskDraft
import io.github.veganmarshmallow.taskboard.core.task.TaskDraftValidator
import io.github.veganmarshmallow.taskboard.core.task.TaskDraftValidator.Field
import io.github.veganmarshmallow.taskboard.core.task.TaskDraftValidator.Result
import io.github.veganmarshmallow.taskboard.core.task.TaskLabels
import io.github.veganmarshmallow.taskboard.core.task.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDraftValidatorTest {
    private fun valid(draft: TaskDraft) = (TaskDraftValidator.validate(draft) as Result.Valid).issue
    private fun errors(draft: TaskDraft) = (TaskDraftValidator.validate(draft) as Result.Invalid).errors

    @Test fun minimalDraftIsATodoFeature() {
        val issue = valid(TaskDraft(title = "Do a thing"))
        assertEquals("Do a thing", issue.title)
        assertEquals(listOf(Labels.TODO, "type: feature"), issue.labels)
        assertTrue(issue.body.startsWith("### Repo\n\n_No response_\n\n### Type\n\nfeature"))
    }

    @Test fun fullDraft() {
        val issue = valid(
            TaskDraft("  Title  ", "Goal", "Done", "my-app", TaskType.RESEARCH, Priority.HIGH, approveNow = true),
        )
        assertEquals("Title", issue.title)
        assertEquals(listOf(Labels.TODO, "type: research", Labels.PRIORITY_HIGH, Labels.READY_FOR_CLAUDE), issue.labels)
        assertTrue(issue.body.contains("### Repo\n\nVeganMarshmallow/my-app\n"))
        assertTrue(issue.body.contains("### What should be done\n\nGoal\n"))
        assertTrue(issue.body.contains("### Done when\n\nDone\n"))
    }

    @Test fun titleIsRequired() {
        assertEquals(setOf(Field.TITLE), errors(TaskDraft(title = "")).keys)
        assertEquals("A title is required", errors(TaskDraft(title = " \n\t ")).getValue(Field.TITLE))
    }

    @Test fun titleLineBreaksBecomeSpaces() {
        assertEquals("a b c", valid(TaskDraft(title = "a\r\nb\tc")).title)
    }

    @Test fun titleLengthBoundaryCountsCodePoints() {
        assertEquals(256, valid(TaskDraft(title = "x".repeat(256))).title.length)
        assertTrue(errors(TaskDraft(title = "x".repeat(257))).getValue(Field.TITLE).contains("257 of 256"))
        assertEquals(256, valid(TaskDraft(title = "  " + "ש".repeat(256) + "  ")).title.length)
        val emoji = "😀".repeat(256) // 512 UTF-16 units, 256 characters
        assertEquals(emoji, valid(TaskDraft(title = emoji)).title)
        assertTrue(errors(TaskDraft(title = emoji + "😀")).containsKey(Field.TITLE))
    }

    @Test fun hebrewAndMarkdownTitles() {
        assertEquals("**לוח** `#9` [x](y)", valid(TaskDraft(title = "**לוח** `#9` [x](y)")).title)
    }

    @Test fun invalidRepoIsAFieldError() {
        val e = errors(TaskDraft(title = "t", repo = "a/b/c"))
        assertEquals(setOf(Field.REPO), e.keys)
        assertTrue(e.getValue(Field.REPO).contains("owner/name"))
        assertEquals(setOf(Field.TITLE, Field.REPO), errors(TaskDraft(title = "", repo = "bad name")).keys)
    }

    @Test fun hugeBodyIsRejectedNotTruncated() {
        assertEquals(setOf(Field.GOAL), errors(TaskDraft(title = "t", goal = "x".repeat(TaskDraftValidator.MAX_BODY))).keys)
        val ok = valid(TaskDraft(title = "t", goal = "x".repeat(60_000)))
        assertTrue(ok.body.length <= TaskDraftValidator.MAX_BODY)
    }

    @Test fun labelsForEveryPriorityAndType() {
        assertEquals(listOf(Labels.TODO, "type: bug"), TaskLabels.of(TaskType.BUG, Priority.NORMAL, false))
        assertEquals(listOf(Labels.TODO, "type: bug", Labels.PRIORITY_LOW), TaskLabels.of(TaskType.BUG, Priority.LOW, false))
        assertEquals(listOf(Labels.TODO, "type: refactor", Labels.READY_FOR_CLAUDE), TaskLabels.of(TaskType.REFACTOR, Priority.NORMAL, true))
        TaskType.entries.forEach { t -> assertTrue(TaskLabels.of(t, Priority.NORMAL, false).contains("type: ${t.value}")) }
    }
}
