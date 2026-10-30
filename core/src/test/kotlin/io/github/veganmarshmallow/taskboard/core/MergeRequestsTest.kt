package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.github.GitHubRequests
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.merge.PullRef
import io.github.veganmarshmallow.taskboard.core.merge.checkRuns
import io.github.veganmarshmallow.taskboard.core.merge.mergePull
import io.github.veganmarshmallow.taskboard.core.merge.openPulls
import io.github.veganmarshmallow.taskboard.core.merge.pull
import io.github.veganmarshmallow.taskboard.core.merge.repository
import io.github.veganmarshmallow.taskboard.core.model.Repo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MergeRequestsTest {
    private val requests = GitHubRequests(Repo.BOARD, TOKEN)
    private val base = "$API_REPOS/VeganMarshmallow/Taskboard-widget"

    @Test fun readsGoToTheProjectRepo() {
        assertEquals("$base/pulls/12", requests.pull(PullRef(WIDGET, 12)).url)
        assertEquals("$base/pulls?state=open&per_page=100", requests.openPulls(WIDGET).url)
        assertEquals("$base/commits/$SHA_A/check-runs?per_page=100", requests.checkRuns(WIDGET, SHA_A).url)
        assertEquals(base, requests.repository(WIDGET).url)
        listOf(requests.pull(PullRef(WIDGET, 1)), requests.openPulls(WIDGET)).forEach {
            assertEquals("GET", it.method)
            assertEquals("Bearer $TOKEN", it.headers["Authorization"])
        }
    }

    @Test fun mergeIsAPutPinnedToTheHeadSha() {
        val r = requests.mergePull(pr(12))
        assertEquals("PUT", r.method)
        assertEquals("$base/pulls/12/merge", r.url)
        assertEquals(mapOf("merge_method" to "merge", "sha" to SHA_A), JsonParser.parse(r.body!!))
        assertEquals("application/json; charset=utf-8", r.headers["Content-Type"])
        assertEquals("2022-11-28", r.headers["X-GitHub-Api-Version"])
    }

    @Test fun unsafeRepoNamesAndShasAreRefused() {
        listOf(Repo("a/b", "c"), Repo("o", "x?y"), Repo("o", ".."), Repo("", "r"), Repo("o", "r#1"), Repo("-o", "r"), Repo("o", "שלום")).forEach { repo ->
            assertThrows(IllegalArgumentException::class.java) { requests.openPulls(repo) }
        }
        assertThrows(IllegalArgumentException::class.java) { requests.checkRuns(WIDGET, "../../x") }
        assertThrows(IllegalArgumentException::class.java) { requests.checkRuns(WIDGET, "abc") }
        assertThrows(IllegalArgumentException::class.java) { PullRef(WIDGET, 0) }
    }
}
