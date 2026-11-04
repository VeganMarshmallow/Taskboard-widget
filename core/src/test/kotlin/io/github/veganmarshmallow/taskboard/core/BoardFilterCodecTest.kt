package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.filter.BoardFilter
import io.github.veganmarshmallow.taskboard.core.filter.BoardFilterCodec
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.model.Priority
import io.github.veganmarshmallow.taskboard.core.model.Repo
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetStateCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #59: the saved filter, and the repo and types the cached board now keeps for it. */
class BoardFilterCodecTest {
    @Test fun roundTrips() {
        val filter = BoardFilter(
            repos = setOf("VeganMarshmallow/site", "a/b"),
            types = setOf("bug"),
            priorities = setOf(Priority.HIGH, Priority.NORMAL),
            text = "café \"quoted\" \\ #12 עברית",
        )
        assertEquals(filter, BoardFilterCodec.decode(BoardFilterCodec.encode(filter)))
        assertEquals(BoardFilter.NONE, BoardFilterCodec.decode(BoardFilterCodec.encode(BoardFilter.NONE)))
    }

    @Test fun unreadableTextMeansNoFilter() {
        listOf(null, "", "   ", "{", "[]", "42", "null", "\"x\"", "{\"repos\":7}").forEach {
            val decoded = BoardFilterCodec.decode(it)
            assertEquals("$it", 0, decoded.activeCount)
        }
    }

    @Test fun missingFieldsAreEmptyAndUnknownValuesSkipped() {
        assertEquals(BoardFilter(repos = setOf("a/b")), BoardFilterCodec.decode("""{"repos":["a/b"]}"""))
        assertEquals(
            BoardFilter(priorities = setOf(Priority.LOW), types = setOf("bug")),
            BoardFilterCodec.decode("""{"v":9,"priorities":["URGENT","LOW",3,null],"types":["bug","",5],"future":{"x":1}}"""),
        )
        assertEquals(BoardFilter.NONE, BoardFilterCodec.decode("""{"text":5,"repos":"a/b"}"""))
    }

    @Test fun cachedBoardKeepsRepoAndTypes() {
        val board = BoardBuilder().build(
            listOf(Issue(3, "t", setOf(Labels.TODO, Labels.type("bug"), Labels.type("feature")), true, repo = Repo("o", "n"))),
            NOW,
        )
        val item = WidgetStateCodec.decode(WidgetStateCodec.encode(WidgetState.INITIAL.withBoard(board))).board!!
            .section(Section.TODO).items.single()
        assertEquals("o/n", item.repo)
        assertEquals(setOf("bug", "feature"), item.types)
    }

    @Test fun boardsSavedBeforeTheFilterStillLoad() {
        val old = """{"v":3,"board":{"fetchedAt":1,"sections":[{"section":"TODO","total":1,""" +
            """"items":[{"n":3,"t":"Old","p":"NORMAL","a":false,"r":null,"m":false}]}]},"error":null,"attemptedAt":1}"""
        val item = WidgetStateCodec.decode(old).board!!.section(Section.TODO).items.single()
        assertEquals(3, item.number)
        assertNull(item.repo)
        assertEquals(emptySet<String>(), item.types)
    }
}
