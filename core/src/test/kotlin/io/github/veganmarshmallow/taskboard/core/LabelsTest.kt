package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.tags.TagPills
import io.github.veganmarshmallow.taskboard.core.task.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins every board label to its exact name, so a rename is a deliberate, test-visible change (#26). */
class LabelsTest {
    @Test fun boardLabelNames() {
        assertEquals("status: todo", Labels.TODO)
        assertEquals("status: in-progress", Labels.IN_PROGRESS)
        assertEquals("status: blocked", Labels.BLOCKED)
        assertEquals("status: in-review", Labels.IN_REVIEW)
        assertEquals("status: done", Labels.DONE)
        assertEquals("ready-for-claude", Labels.READY_FOR_CLAUDE)
        assertEquals("needs-owner", Labels.NEEDS_OWNER)
        assertEquals("created-by-claude", Labels.CREATED_BY_CLAUDE)
        assertEquals("priority: high", Labels.PRIORITY_HIGH)
        assertEquals("priority: low", Labels.PRIORITY_LOW)
        assertEquals("status:", Labels.STATUS_PREFIX)
        assertEquals("type:", Labels.TYPE_PREFIX)
    }

    @Test fun typeLabels() {
        assertEquals(
            listOf("type: feature", "type: bug", "type: refactor", "type: research"),
            TaskType.entries.map { it.label },
        )
        assertEquals("bug", Labels.typeValue("type: bug"))
        assertEquals("bug", Labels.typeValue("type:bug"))
        assertEquals("", Labels.typeValue("type:"))
        assertNull(Labels.typeValue("bug"))
    }

    @Test fun statusDetection() {
        assertTrue(Labels.isStatus("status: todo"))
        assertTrue(Labels.isStatus("status:custom"))
        assertFalse(Labels.isStatus("priority: high"))
        assertFalse(Labels.isStatus("my status: x"))
    }

    /** Reviewer edge cases (#26): blank, prefix-only, unicode, case and spacing, as on main. */
    @Test fun edgeCases() {
        assertFalse(Labels.isStatus(""))
        assertFalse(Labels.isStatus("   "))
        assertTrue(Labels.isStatus("status:"))
        assertFalse(Labels.isStatus("status :todo"))
        // The helpers expect normalized names; callers normalize first, so case is handled there.
        assertFalse(Labels.isStatus("Status: todo"))
        assertTrue(Labels.isStatus(Labels.normalize("  STATUS: Todo ")))
        assertEquals(Labels.TODO, Labels.normalize(" Status: TODO "))

        assertNull(Labels.typeValue(""))
        assertNull(Labels.typeValue("   "))
        assertNull(Labels.typeValue("types: bug"))
        assertNull(Labels.typeValue("Type: bug"))
        assertEquals("bug", Labels.typeValue(Labels.normalize("Type: BUG")))
        assertEquals("", Labels.typeValue("type: "))
        assertEquals("", Labels.typeValue("type:   "))
        assertEquals("באג 🐛", Labels.typeValue("type: באג 🐛"))
        assertEquals("type: ", Labels.type(""))
        assertEquals("type: באג", Labels.type("באג"))
    }

    @Test fun tagPillsHideBlankPrefixOnlyAndStatusLabels() {
        val pills = TagPills.of(
            mapOf("" to null, "   " to null, "status:" to null, " Status:Todo " to null, "type:" to null, "TYPE: Bug" to null),
            null,
        )
        assertEquals(listOf("Bug"), pills.map { it.text })
    }
}
