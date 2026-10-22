package io.github.veganmarshmallow.taskboard

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.BoardLoader
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.widget.RowAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.ZoneOffset

/** Storage, intents and no-token behaviour on a real device. */
@RunWith(AndroidJUnit4::class)
class AndroidPiecesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun cleanUp() {
        TokenStore(context).clear()
        StateStore(context).save(WidgetState.INITIAL)
    }

    @Test fun stateSurvivesANewStoreInstanceLikeAfterProcessDeath() {
        val title = FakeBoards.HEBREW_TITLE + " 👩‍👩‍👧 \uD800 \u0001 ‮"
        val board = BoardBuilder().build(listOf(Issue(2, title, setOf(Labels.TODO), true)), FakeBoards.NOW)
        val state = WidgetState(board, BoardError.Network, FakeBoards.NOW)
        StateStore(context).save(state)
        // SharedPreferences writes XML; a lone surrogate in a title would have corrupted the file.
        assertEquals(state, StateStore(context).load())
    }

    @Test fun noTokenMeansNoApiAndANoTokenState() {
        TokenStore(context).clear()
        assertNull(AppGraph.apiOrNull(context))
        TokenStore(context).save("   ")
        assertNull(AppGraph.apiOrNull(context)) // blank counts as no token
        val state = BoardLoader(Clock.fixed(FakeBoards.NOW, ZoneOffset.UTC)).refresh(StateStore(context).load(), AppGraph.apiOrNull(context))
        assertEquals(BoardError.NoToken, state.error)
    }

    @Test fun rowActionsRoundTripAndRejectAnythingElse() {
        val open = RowAction.Open("https://github.com/VeganMarshmallow/claude-workspace/issues/6")
        assertEquals(open, RowAction.from(open.toFillIn()))
        val approve = RowAction.Approve(6, FakeBoards.HEBREW_TITLE)
        assertEquals(approve, RowAction.from(approve.toFillIn()))

        assertNull(RowAction.from(null))
        assertNull(RowAction.from(Intent()))
        assertNull(RowAction.from(Intent().putExtra("row_kind", "delete")))
        listOf("http://github.com/x", "https://github.com.evil.example/x", "javascript:alert(1)", "intent://x", "").forEach {
            assertNull(it, RowAction.from(Intent().putExtra("row_kind", "open").putExtra("row_url", it)))
        }
        assertNull(RowAction.from(Intent().putExtra("row_kind", "open")))
        listOf(0, -4).forEach { assertNull(RowAction.from(Intent().putExtra("row_kind", "approve").putExtra("row_issue", it))) }
        assertEquals(RowAction.Approve(3, ""), RowAction.from(Intent().putExtra("row_kind", "approve").putExtra("row_issue", 3)))
    }
}
