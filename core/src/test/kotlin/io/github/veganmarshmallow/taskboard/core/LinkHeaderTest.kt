package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.github.LinkHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkHeaderTest {
    private val base = "https://api.github.com/repositories/123/issues"

    @Test fun parsesGitHubStyleHeader() {
        val header = """<$base?page=2&per_page=100>; rel="next", <$base?page=5&per_page=100>; rel="last""""
        assertEquals("$base?page=2&per_page=100", LinkHeader.nextUrl(header))
        assertEquals("$base?page=5&per_page=100", LinkHeader.relations(header)["last"])
    }

    @Test fun lastPageHasNoNext() {
        val header = """<$base?page=1>; rel="first", <$base?page=4>; rel="prev""""
        assertNull(LinkHeader.nextUrl(header))
    }

    @Test fun missingOrBlankHeader() {
        assertNull(LinkHeader.nextUrl(null))
        assertNull(LinkHeader.nextUrl(""))
        assertNull(LinkHeader.nextUrl("   "))
    }

    @Test fun malformedHeadersYieldNoNext() {
        listOf(
            "garbage",
            "$base?page=2; rel=\"next\"",          // no angle brackets
            "<$base?page=2>",                       // no rel
            "<>; rel=\"next\"",                     // empty url
            "<$base?page=2>; title=\"next\"",       // wrong param
            ";;;,,,",
            "<$base?page=2; rel=\"next\"",          // unclosed bracket
        ).forEach { assertNull("header: $it", LinkHeader.nextUrl(it)) }
    }

    @Test fun toleratesSpacingCaseAndUnquotedRel() {
        assertEquals("$base?page=3", LinkHeader.nextUrl("  <$base?page=3>;rel=next"))
        assertEquals("$base?page=3", LinkHeader.nextUrl("<$base?page=3> ; REL = \"Next\""))
    }

    @Test fun handlesMultipleRelValuesAndCommasInUrl() {
        assertEquals("$base?a=1,2&page=2", LinkHeader.nextUrl("<$base?a=1,2&page=2>; rel=\"next last\""))
    }

    @Test fun skipsMalformedEntryButKeepsValidOne() {
        assertEquals("$base?page=2", LinkHeader.nextUrl("junk, <$base?page=2>; rel=\"next\""))
    }

    @Test fun firstNextWins() {
        assertEquals("$base?page=2", LinkHeader.nextUrl("<$base?page=2>; rel=\"next\", <$base?page=9>; rel=\"next\""))
    }
}
