package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.update.AvailableUpdate
import io.github.veganmarshmallow.taskboard.core.update.ReleasePage
import io.github.veganmarshmallow.taskboard.core.update.UpdateError
import io.github.veganmarshmallow.taskboard.core.update.UpdateStatus
import io.github.veganmarshmallow.taskboard.core.update.VersionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasePageTest {
    @Test fun fallbackIsTheLatestReleaseOfTheAppRepo() {
        assertEquals("https://github.com/VeganMarshmallow/Taskboard-widget/releases/tag/latest", ReleasePage.FALLBACK)
        assertTrue(ReleasePage.isSafe(ReleasePage.FALLBACK))
    }

    @Test fun acceptsOnlyHttpsPagesOfTheAppRepo() {
        assertTrue(ReleasePage.isSafe(RELEASE_PAGE))
        assertTrue(ReleasePage.isSafe("https://github.com/VeganMarshmallow/Taskboard-widget/releases/tag/untagged-1a2b"))
        listOf(
            null, "", " ", "latest", "/VeganMarshmallow/Taskboard-widget/releases",
            "http://github.com/VeganMarshmallow/Taskboard-widget/releases/tag/latest",
            "https://github.com/VeganMarshmallow/Taskboard-widget", // no trailing slash: not a page under the repo
            "https://github.com/VeganMarshmallow/Taskboard-widget-evil/releases",
            "https://github.com/VeganMarshmallow/claude-workspace/issues/1",
            "https://github.com.evil.example/VeganMarshmallow/Taskboard-widget/releases",
            "https://evil.example/https://github.com/VeganMarshmallow/Taskboard-widget/",
            "javascript:alert(1)//https://github.com/VeganMarshmallow/Taskboard-widget/",
            "intent://github.com/VeganMarshmallow/Taskboard-widget/#Intent;end",
            "https://github.com/VeganMarshmallow/Taskboard-widget/releases tag",
            "https://github.com/VeganMarshmallow/Taskboard-widget/releases\n",
            "https://github.com/VeganMarshmallow/Taskboard-widget/\u0000",
            "https://github.com/VeganMarshmallow/Taskboard-widget/" + "x".repeat(5000),
        ).forEach { assertFalse(it.toString(), ReleasePage.isSafe(it)) }
    }

    @Test fun orFallback() {
        assertEquals(RELEASE_PAGE, ReleasePage.orFallback(RELEASE_PAGE))
        assertEquals(ReleasePage.FALLBACK, ReleasePage.orFallback(null))
        assertEquals(ReleasePage.FALLBACK, ReleasePage.orFallback("https://evil.example/"))
        // Unicode in a path segment is fine (GitHub tags can be any text).
        val hebrew = "https://github.com/VeganMarshmallow/Taskboard-widget/releases/tag/גרסה"
        assertEquals(hebrew, ReleasePage.orFallback(hebrew))
    }

    @Test fun toOpenUsesTheOfferedUpdatesPage() {
        val page = "https://github.com/VeganMarshmallow/Taskboard-widget/releases/tag/untagged-9"
        val status = UpdateStatus(NOW, AvailableUpdate(VersionInfo(70, "0.1.70"), page))
        assertEquals(page, ReleasePage.toOpen(status, 68))
        // Nothing newer offered (or never checked): the latest release page.
        assertEquals(ReleasePage.FALLBACK, ReleasePage.toOpen(status, 70))
        assertEquals(ReleasePage.FALLBACK, ReleasePage.toOpen(UpdateStatus.NEVER, 1))
        // A failed check keeps the known update, and its page.
        assertEquals(page, ReleasePage.toOpen(status.copy(error = UpdateError.Network), 68))
    }
}
