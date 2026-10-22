package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.BoardItem
import io.github.veganmarshmallow.taskboard.core.board.BoardLinks
import io.github.veganmarshmallow.taskboard.core.board.BoardRow
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.model.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardLinksTest {
    private val links = BoardLinks()
    private val base = "https://github.com/VeganMarshmallow/claude-workspace/issues"

    @Test fun itemOpensIssuePage() {
        val row = BoardRow.Item(BoardItem(6, "t", Priority.NORMAL, canApprove = true))
        assertEquals("$base/6", links.forRow(row))
    }

    @Test fun otherRows() {
        assertNull(links.forRow(BoardRow.Header(Section.TODO, 3)))
        assertEquals(base, links.forRow(BoardRow.More(Section.TODO, 3)))
        assertEquals("$base/new", links.forRow(BoardRow.Empty))
        assertEquals("$base?q=is%3Aissue%20is%3Aclosed%20label%3A%22status%3A%20done%22", links.forRow(BoardRow.Done(2)))
    }
}
