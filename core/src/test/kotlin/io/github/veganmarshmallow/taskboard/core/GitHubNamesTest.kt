package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.model.GitHubNames
import org.junit.Assert.assertFalse
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
