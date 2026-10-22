package io.github.veganmarshmallow.taskboard

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.veganmarshmallow.taskboard.core.FakeHttpOnDevice
import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.BoardLinks
import io.github.veganmarshmallow.taskboard.core.board.BoardRow
import io.github.veganmarshmallow.taskboard.core.board.BoardRows
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.board.TabSelection
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.github.GitHubRequests
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.model.Repo
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetStateCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.ZoneOffset

/**
 * Runs the core's whole path on Android's own class library. JVM unit tests can't catch calls to
 * JDK methods Android lacks (URLEncoder.encode(String, Charset) is API 33+ and crashed this app).
 */
@RunWith(AndroidJUnit4::class)
class CoreOnDeviceTest {
    @Test fun requestUrlsAndLinksBuildOnAndroid() {
        val requests = GitHubRequests(Repo.BOARD, "github_pat_X")
        assertTrue(requests.openIssues().url.endsWith("issues?state=open&per_page=100"))
        assertTrue(requests.recentlyDone(FakeBoards.NOW).url.contains("labels=status%3A%20done"))
        assertTrue(BoardLinks().recentlyDone().contains("%22status%3A%20done%22"))
        assertTrue(BoardLinks().forRow(BoardRow.More(Section.DONE, 3))!!.startsWith("https://github.com/"))
    }

    @Test fun fullFetchBuildAndCacheOnAndroid() {
        val http = FakeHttpOnDevice()
        val api = GitHubApi(http, "github_pat_X", Clock.fixed(FakeBoards.NOW, ZoneOffset.UTC))
        val issues = (api.fetchIssues() as Outcome.Success).value
        val board = BoardBuilder().build(issues, FakeBoards.NOW)
        assertEquals(2, board.sections.sumOf { it.totalCount })
        assertEquals(1, board.doneLast7Days)
        assertTrue(BoardRows.of(board, Section.DONE).single() is BoardRow.Item)
        assertEquals(TabSelection.resolve(null, board), TabSelection.parse(TabSelection.key(TabSelection.default(board))))
        val state = WidgetState(board, null, FakeBoards.NOW)
        assertEquals(state, WidgetStateCodec.decode(WidgetStateCodec.encode(state)))
        assertEquals(3, http.requests) // two open pages + the done listing
    }
}
