package io.github.veganmarshmallow.taskboard

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.veganmarshmallow.taskboard.core.FakeHttpOnDevice
import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.BoardLinks
import io.github.veganmarshmallow.taskboard.core.board.BoardRow
import io.github.veganmarshmallow.taskboard.core.board.BoardRows
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.github.GitHubRequests
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.model.Repo
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetStateCodec
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.Redirects
import io.github.veganmarshmallow.taskboard.core.update.DownloadVerifier
import io.github.veganmarshmallow.taskboard.core.update.UpdateMessages
import io.github.veganmarshmallow.taskboard.core.update.VersionJsonParser
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
        assertTrue(BoardLinks().forRow(BoardRow.Done(3))!!.startsWith("https://github.com/"))
    }

    @Test fun fullFetchBuildAndCacheOnAndroid() {
        val http = FakeHttpOnDevice()
        val api = GitHubApi(http, "github_pat_X", Clock.fixed(FakeBoards.NOW, ZoneOffset.UTC))
        val issues = (api.fetchIssues() as Outcome.Success).value
        val board = BoardBuilder().build(issues, FakeBoards.NOW)
        assertEquals(2, board.sections.sumOf { it.totalCount })
        assertEquals(1, board.doneLast7Days)
        assertTrue(BoardRows.of(board).last() is BoardRow.Done)
        val state = WidgetState(board, null, FakeBoards.NOW)
        assertEquals(state, WidgetStateCodec.decode(WidgetStateCodec.encode(state)))
        assertEquals(3, http.requests) // two open pages + the done listing
    }

    /** The updater's URI, SHA-256 and formatting calls on Android's class library (#14). */
    @Test fun updaterCoreRunsOnAndroid() {
        val first = HttpRequest("GET", "https://api.github.com/repos/o/r/releases/assets/1", mapOf("Authorization" to "Bearer x", "Accept" to "application/octet-stream"))
        val signed = "https://release-assets.githubusercontent.com/p/1?sp=r&se=2026-10-22T18%3A26%3A59Z&sig=a%2Bb"
        val next = Redirects.next(first, signed)!!
        assertEquals(signed, next.url)
        assertEquals(setOf("Accept"), next.headers.keys)
        assertEquals(first.headers, Redirects.next(first, "/repositories/9/releases/assets/1")!!.headers)
        assertEquals(null, Redirects.next(first, "http://x.example/"))

        val bytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        // SHA-256 of PK\x03\x04
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val verifier = DownloadVerifier(4, sha)
        verifier.update(bytes, 4)
        assertEquals(null, verifier.problem())

        val v = VersionJsonParser.parse("""{"versionCode":42,"versionName":"0.1.42","apkSha256":"$sha","apkSize":4}""")
        assertEquals(42L, v.versionCode)
        assertEquals("0.7 MB", UpdateMessages.megabytes(704_244))
    }
}
