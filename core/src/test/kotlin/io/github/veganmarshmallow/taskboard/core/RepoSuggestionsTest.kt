package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.model.Repo
import io.github.veganmarshmallow.taskboard.core.task.RepoSuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepoSuggestionsTest {
    private fun form(repo: String) = "### Repo\n\n$repo\n\n### Type\n\nfeature\n"

    @Test fun readsTheFormSection() {
        assertEquals(Repo("VeganMarshmallow", "Taskboard-widget"), RepoSuggestions.repoIn(form("VeganMarshmallow/Taskboard-widget")))
        assertEquals(Repo("o", "r"), RepoSuggestions.repoIn("### Repo\r\n\r\no/r\r\n"))
        assertEquals(Repo("o", "r"), RepoSuggestions.repoIn(form("https://github.com/o/r")))
    }

    @Test fun readsARepoLine() {
        assertEquals(Repo("VeganMarshmallow", "Taskboard-widget"), RepoSuggestions.repoIn("Repo: VeganMarshmallow/Taskboard-widget\n\nFound during"))
        assertEquals(Repo("o", "r"), RepoSuggestions.repoIn("**Repo:** `o/r`."))
    }

    @Test fun ignoresNonRepos() {
        assertNull(RepoSuggestions.repoIn(form("_No response_")))
        assertNull(RepoSuggestions.repoIn(form("_No response_ — example only")))
        assertNull(RepoSuggestions.repoIn("### Repo\n\n### Type\n\nfeature"))
        assertNull(RepoSuggestions.repoIn(form("just-a-name")))
        assertNull(RepoSuggestions.repoIn(""))
        assertNull(RepoSuggestions.repoIn("Nothing about repos, ### Repo inline"))
        assertNull(RepoSuggestions.repoIn(form("פרויקט/שלי")))
    }

    @Test fun countsDedupesCaseInsensitivelyAndOrdersByUse() {
        val bodies = listOf(
            form("me/new"),
            "Repo: VeganMarshmallow/taskboard-widget",
            form("VeganMarshmallow/Taskboard-widget"),
            null,
            form("_No response_"),
            form("me/old"),
        )
        assertEquals(listOf("VeganMarshmallow/taskboard-widget", "me/new", "me/old"), RepoSuggestions.fromBodies(bodies))
    }

    @Test fun capsTheList() {
        val bodies = (1..50).map { form("o/r$it") }
        assertEquals(RepoSuggestions.MAX, RepoSuggestions.fromBodies(bodies).size)
        assertEquals(emptyList<String>(), RepoSuggestions.fromBodies(emptyList()))
    }

    @Test fun matchingFilters() {
        val all = listOf("VeganMarshmallow/Taskboard-widget", "me/site")
        assertEquals(all, RepoSuggestions.matching(all, "  "))
        assertEquals(listOf("VeganMarshmallow/Taskboard-widget"), RepoSuggestions.matching(all, "board"))
        assertEquals(listOf("me/site"), RepoSuggestions.matching(all, "ME/S"))
        assertEquals(emptyList<String>(), RepoSuggestions.matching(all, "zzz"))
    }
}
