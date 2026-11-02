package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.github.GitHubRequests
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.merge.PullRef
import io.github.veganmarshmallow.taskboard.core.merge.compare
import io.github.veganmarshmallow.taskboard.core.merge.mergePull
import io.github.veganmarshmallow.taskboard.core.merge.openPulls
import io.github.veganmarshmallow.taskboard.core.merge.pull
import io.github.veganmarshmallow.taskboard.core.merge.pullRequestRuns
import io.github.veganmarshmallow.taskboard.core.merge.repository
import io.github.veganmarshmallow.taskboard.core.merge.workflowRuns
import io.github.veganmarshmallow.taskboard.core.merge.workflows
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
        assertEquals("$base/actions/runs?head_sha=$SHA_A&per_page=100", requests.workflowRuns(WIDGET, SHA_A).url)
        assertEquals(base, requests.repository(WIDGET).url)
        listOf(requests.pull(PullRef(WIDGET, 1)), requests.openPulls(WIDGET), requests.workflowRuns(WIDGET, SHA_A)).forEach {
            assertEquals("GET", it.method)
            assertEquals("Bearer $TOKEN", it.headers["Authorization"])
        }
    }

    @Test fun expectedWorkflowReadsGoToTheProjectRepo() {
        assertEquals(
            "$base/actions/runs?branch=claude/41-expected&event=pull_request&per_page=100",
            requests.pullRequestRuns(WIDGET, "claude/41-expected").url,
        )
        assertEquals("$base/actions/workflows?per_page=100", requests.workflows(WIDGET).url)
        listOf("", "a b", "x&event=push", "../main", "/x", "x/", "a?b", "x#y").forEach { branch ->
            assertThrows(branch, IllegalArgumentException::class.java) { requests.pullRequestRuns(WIDGET, branch) }
        }
        assertThrows(IllegalArgumentException::class.java) { requests.workflows(Repo("o", "r/../x")) }
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
        assertThrows(IllegalArgumentException::class.java) { requests.workflowRuns(WIDGET, "../../x") }
        assertThrows(IllegalArgumentException::class.java) { requests.workflowRuns(WIDGET, "abc") }
        assertThrows(IllegalArgumentException::class.java) { requests.workflowRuns(WIDGET, "$SHA_A&actor=x") }
        assertThrows(IllegalArgumentException::class.java) { requests.workflowRuns(Repo("o", "r/../x"), SHA_A) }
        assertThrows(IllegalArgumentException::class.java) { PullRef(WIDGET, 0) }
    }

    @Test fun compareAgainstTheDefaultBranch() {
        assertEquals("$base/compare/main...$SHA_A", requests.compare(WIDGET, "main", SHA_A).url)
        assertEquals("$base/compare/release/v2...$SHA_A", requests.compare(WIDGET, "release/v2", SHA_A).url)
        listOf("", "../x", "a..b", "/main", "main/", "main?x=1", "main#x", "ma in").forEach { branch ->
            assertThrows(branch, IllegalArgumentException::class.java) { requests.compare(WIDGET, branch, SHA_A) }
        }
        assertThrows(IllegalArgumentException::class.java) { requests.compare(WIDGET, "main", "not-a-sha") }
    }
}
