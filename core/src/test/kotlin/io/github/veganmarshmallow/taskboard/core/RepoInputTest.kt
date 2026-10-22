package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.model.Repo
import io.github.veganmarshmallow.taskboard.core.task.RepoInput
import io.github.veganmarshmallow.taskboard.core.task.RepoInput.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoInputTest {
    private fun valid(input: String) = (RepoInput.parse(input) as Result.Valid).repo
    private fun invalid(input: String) = (RepoInput.parse(input) as Result.Invalid).reason

    @Test fun emptyAndBlankAreEmpty() {
        assertEquals(Result.Empty, RepoInput.parse(""))
        assertEquals(Result.Empty, RepoInput.parse("   \n\t"))
    }

    @Test fun ownerAndName() {
        assertEquals(Repo("VeganMarshmallow", "Taskboard-widget"), valid("VeganMarshmallow/Taskboard-widget"))
        assertEquals(Repo("a", "b"), valid("  a/b  "))
        assertEquals(Repo("some-org", "my_app.v2"), valid("some-org/my_app.v2"))
    }

    @Test fun bareNameDefaultsTheOwner() {
        assertEquals(Repo("VeganMarshmallow", "my-app"), valid("my-app"))
        assertEquals(Repo("other", "x"), (RepoInput.parse("x", defaultOwner = "other") as Result.Valid).repo)
    }

    @Test fun pastedLinksAndGitSuffixAreAccepted() {
        assertEquals(Repo("o", "r"), valid("https://github.com/o/r"))
        assertEquals(Repo("o", "r"), valid("https://github.com/o/r/"))
        assertEquals(Repo("o", "r"), valid("http://www.GitHub.com/o/r.git"))
        assertEquals(Repo("o", "r"), valid("github.com/o/r"))
        assertEquals(Repo("VeganMarshmallow", "r"), valid("r.git"))
    }

    @Test fun missingParts() {
        assertTrue(invalid("/name").startsWith("Owner is missing"))
        assertTrue(invalid("owner/").startsWith("Repo name is missing"))
        assertTrue(invalid("/").startsWith("Owner is missing"))
        assertTrue(invalid("a/b/c").startsWith("Too many slashes"))
        assertTrue(invalid("https://github.com/o/r/issues/3").startsWith("Too many slashes"))
    }

    @Test fun invalidOwners() {
        listOf("-ab", "ab-", "a--b", "a_b", "a.b", "a b", "אבג", "a".repeat(40)).forEach {
            assertTrue(it, invalid("$it/repo").contains("valid GitHub owner"))
        }
        assertEquals(Repo("a".repeat(39), "r"), valid("${"a".repeat(39)}/r"))
    }

    @Test fun invalidNames() {
        listOf("my app", "a#b", "פרויקט", "x?y", ".", "..", "a".repeat(101), "`a`", "a*b").forEach {
            assertTrue(it, invalid("o/$it").contains("valid repo name"))
        }
        assertEquals(Repo("o", "a".repeat(100)), valid("o/${"a".repeat(100)}"))
        assertEquals(Repo("o", ".github"), valid("o/.github"))
    }

    @Test fun reasonsIncludeAFormatHint() {
        assertTrue(invalid("a/b/c").contains("owner/name"))
    }

    @Test fun strictRequiresAnOwner() {
        assertEquals(Repo("o", "r"), RepoInput.parseStrict("o/r"))
        assertNull(RepoInput.parseStrict("r"))
        assertNull(RepoInput.parseStrict("_No response_"))
        assertNull(RepoInput.parseStrict("a/b/c"))
        assertNull(RepoInput.parseStrict(""))
    }
}
