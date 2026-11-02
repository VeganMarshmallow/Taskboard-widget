package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.model.GitHubNames
import io.github.veganmarshmallow.taskboard.core.tags.RepoField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every GitHub name rule in one place (#36). The parsers and request builders that use [GitHubNames]
 * (RepoField, RepoInput, PrLinks, MergeRequests, PullJsonParser) keep their own tests for how they
 * wire the rules in and what they report.
 */
class GitHubNamesTest {
    @Test fun owners() {
        listOf("o", "VeganMarshmallow", "owner-1", "a1", "9", "a".repeat(39), "a-", "a--b").forEach {
            assertTrue(it, GitHubNames.isOwner(it))
        }
        listOf("", "-o", "-ab", "a_b", "a.b", "a b", "a/b", "אבג", "a".repeat(40), "o?x", "o#1", ".", "..").forEach {
            assertFalse(it, GitHubNames.isOwner(it))
        }
    }

    @Test fun typedOwnersAlsoFollowTheSignUpRule() {
        listOf("o", "VeganMarshmallow", "owner-1", "a-b-c", "a".repeat(39)).forEach {
            assertTrue(it, GitHubNames.isTypedOwner(it))
        }
        // Accepted from GitHub's own responses, refused when typed.
        listOf("ab-", "a--b").forEach {
            assertTrue(it, GitHubNames.isOwner(it))
            assertFalse(it, GitHubNames.isTypedOwner(it))
        }
        listOf("", "-ab", "a_b", "a.b", "a b", "אבג", "a".repeat(40)).forEach {
            assertFalse(it, GitHubNames.isTypedOwner(it))
        }
    }

    @Test fun repoNames() {
        listOf("r", "Taskboard-widget", "my.site_v2", "a.b.c", "...", ".x", "x.", "-", "_", "a".repeat(100)).forEach {
            assertTrue(it, GitHubNames.isName(it))
        }
        listOf("", "my app", "a#b", "r#1", "x?y", "a/b", "r/../x", "פרויקט", "שלום", "`a`", "a*b", "a".repeat(101)).forEach {
            assertFalse(it, GitHubNames.isName(it))
        }
    }

    @Test fun dotAndDotDotAreNotNamesBecauseTheyChangeTheUrlPath() {
        assertFalse(GitHubNames.isName("."))
        assertFalse(GitHubNames.isName(".."))
    }

    @Test fun lineEndingsControlCharsAndEncodedDotsAreRefused() {
        listOf("o\n", "o\r", "o\u0085", "\no", "o%2e", "O\t").forEach { assertFalse(it, GitHubNames.isOwner(it)) }
        listOf("r\n", "r\r\n", "r\u0085", "r\u2028", "%2e%2e", "%2E", "r\u0000").forEach { assertFalse(it, GitHubNames.isName(it)) }
        listOf("main\n", "a%2e%2e/b", "%2e%2e").forEach { assertFalse(it, GitHubNames.isBranch(it)) }
        assertTrue(GitHubNames.isOwner("VEGAN"))
        assertTrue(GitHubNames.isName("MY.APP"))
    }

    /** RepoField splits on `/` and asks [GitHubNames] (#36); the old one-regex parse took the same inputs. */
    @Test fun repoFieldParseAcceptsTheSameShapesAsBefore() {
        assertEquals("name", RepoField.parse("owner/name"))
        assertEquals("name", RepoField.parse("owner/name.git"))
        assertEquals("name", RepoField.parse("owner/name/issues/3"))
        assertEquals("name", RepoField.parse("https://github.com/owner/name?tab=x"))
        assertEquals("a".repeat(100), RepoField.parse("o/" + "a".repeat(100)))
        listOf("", "owner", "/name", "owner/", "owner/.", "owner/..", "owner/.git", "-o/name", "o_x/name", "${"a".repeat(40)}/n",
            "o/" + "a".repeat(101), "o/%2e%2e", "בעלים/שם", "o/שם", "o /name")
            .forEach { assertNull(it, RepoField.parse(it)) }
    }

    @Test fun shas() {
        listOf("abcdef0", "ABCDEF0", "0".repeat(40), "f".repeat(64)).forEach { assertTrue(it, GitHubNames.SHA.matches(it)) }
        listOf("", "abc", "abcdef", "not-a-sha", "../../x", "g".repeat(40), "0".repeat(65), "${"a".repeat(40)}&actor=x", "${"a".repeat(40)}\n")
            .forEach { assertFalse(it, GitHubNames.SHA.matches(it)) }
    }

    @Test fun branches() {
        listOf("main", "release/v2", "claude/36-shared-names", "v1.2", "a".repeat(200)).forEach {
            assertTrue(it, GitHubNames.isBranch(it))
        }
        listOf("", "../x", "a..b", "/main", "main/", "main?x=1", "main#x", "ma in", "a".repeat(201), "ענף").forEach {
            assertFalse(it, GitHubNames.isBranch(it))
        }
    }
}
