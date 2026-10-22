package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.github.GitHubRequests
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.model.Repo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRequestsTest {
    private val requests = GitHubRequests(Repo.BOARD, TOKEN)

    @Test fun approveRequest() {
        val r = requests.approve(6)
        assertEquals("POST", r.method)
        assertEquals("https://api.github.com/repos/VeganMarshmallow/claude-workspace/issues/6/labels", r.url)
        assertEquals(mapOf("labels" to listOf("ready-for-claude")), JsonParser.parse(r.body!!))
        assertEquals("Bearer $TOKEN", r.headers["Authorization"])
        assertTrue(r.headers["Content-Type"]!!.startsWith("application/json"))
        assertEquals("application/vnd.github+json", r.headers["Accept"])
    }

    @Test fun approveRejectsBadNumbers() {
        assertThrows(IllegalArgumentException::class.java) { requests.approve(0) }
        assertThrows(IllegalArgumentException::class.java) { requests.approve(-1) }
    }

    @Test fun openIssuesRequest() {
        val r = requests.openIssues()
        assertEquals("GET", r.method)
        assertEquals("https://api.github.com/repos/VeganMarshmallow/claude-workspace/issues?state=open&per_page=100", r.url)
        assertNull(r.body)
    }

    @Test fun recentlyDoneRequestEncodesLabelAndSince() {
        val r = requests.recentlyDone(NOW.minusMillis(1500))
        assertEquals(
            "https://api.github.com/repos/VeganMarshmallow/claude-workspace/issues" +
                "?state=closed&labels=status%3A%20done&since=2026-10-22T11%3A59%3A58Z&per_page=100",
            r.url,
        )
    }

    @Test fun pageRequestOnlyAllowsGitHubApi() {
        assertEquals("https://api.github.com/repositories/1/issues?page=2", requests.page("https://api.github.com/repositories/1/issues?page=2").url)
        listOf("https://evil.example/x", "http://api.github.com/x", "https://api.github.com.evil.example/x", "").forEach {
            assertThrows(IllegalArgumentException::class.java) { requests.page(it) }
        }
    }

    @Test fun blankTokenRejected() {
        assertThrows(IllegalArgumentException::class.java) { GitHubRequests(Repo.BOARD, "  ") }
    }

    @Test fun toStringNeverLeaksToken() {
        assertFalse(requests.approve(6).toString().contains(TOKEN))
        assertFalse(requests.openIssues().toString().contains(TOKEN))
    }

    @Test fun issueUrls() {
        assertEquals("https://github.com/VeganMarshmallow/claude-workspace/issues/6", Repo.BOARD.issueUrl(6))
    }
}
