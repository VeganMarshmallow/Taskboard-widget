package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.merge.PrLinks
import io.github.veganmarshmallow.taskboard.core.merge.PullRef
import io.github.veganmarshmallow.taskboard.core.model.Repo
import org.junit.Assert.assertEquals
import org.junit.Test

class PrLinksTest {
    private val known = listOf(WIDGET)
    private fun parse(text: String, repos: List<Repo> = known) = PrLinks.parse(text, repos)
    private fun ref(n: Int, repo: Repo = WIDGET) = PullRef(repo, n)

    @Test fun plainUrl() {
        assertEquals(listOf(ref(12)), parse("PR https://github.com/VeganMarshmallow/Taskboard-widget/pull/12"))
    }

    @Test fun urlVariants() {
        listOf(
            "http://github.com/VeganMarshmallow/Taskboard-widget/pull/12",
            "https://www.github.com/VeganMarshmallow/Taskboard-widget/pull/12",
            "https://github.com/VeganMarshmallow/Taskboard-widget/pull/12/files",
            "https://github.com/VeganMarshmallow/Taskboard-widget/pull/12#issuecomment-1",
            "https://github.com/VeganMarshmallow/Taskboard-widget/pull/12?w=1",
            "(https://github.com/VeganMarshmallow/Taskboard-widget/pull/12).",
            "[PR](https://github.com/VeganMarshmallow/Taskboard-widget/pull/12)",
            "<https://github.com/VeganMarshmallow/Taskboard-widget/pull/12>",
            "HTTPS://GITHUB.COM/VeganMarshmallow/Taskboard-widget/pull/12",
        ).forEach { assertEquals(it, listOf(ref(12)), parse(it)) }
    }

    @Test fun hebrewAroundTheLink() {
        val text = "🤖 ה-PR מוכן: https://github.com/VeganMarshmallow/Taskboard-widget/pull/7 — תודה!"
        assertEquals(listOf(ref(7)), parse(text))
        assertEquals(listOf(ref(8)), parse("נפתח Taskboard-widget PR #8 לבדיקה"))
    }

    @Test fun shortFormNeedsAKnownRepoOrAnOwner() {
        assertEquals(listOf(ref(3)), parse("Taskboard-widget PR #3 is ready"))
        assertEquals(listOf(ref(3)), parse("taskboard-widget pr #3"))
        assertEquals(listOf(ref(4, Repo("Someone", "other"))), parse("Someone/other PR #4"))
        assertEquals(emptyList<PullRef>(), parse("merge the PR #3"))
        assertEquals(emptyList<PullRef>(), parse("Taskboard-widget PR #3", repos = emptyList()))
    }

    @Test fun malformedLinksAreSkipped() {
        listOf(
            "https://github.com/VeganMarshmallow/Taskboard-widget/pull/0",
            "https://github.com/VeganMarshmallow/Taskboard-widget/pull/abc",
            "https://github.com/VeganMarshmallow/Taskboard-widget/pull/1234567890",
            "https://github.com/VeganMarshmallow/Taskboard-widget/pulls",
            "https://github.com/VeganMarshmallow/Taskboard-widget/issues/12",
            "https://gitlab.com/VeganMarshmallow/Taskboard-widget/pull/12",
            "https://github.com.evil.com/VeganMarshmallow/Taskboard-widget/pull/12",
            "https://notgithub.com/VeganMarshmallow/Taskboard-widget/pull/12",
            "https://github.com/-bad/Taskboard-widget/pull/12",
            "https://github.com/VeganMarshmallow/../pull/12",
            "Taskboard-widget PR #",
            "",
        ).forEach { assertEquals(it, emptyList<PullRef>(), parse(it)) }
    }

    @Test fun readingOrderAndNoDuplicates() {
        val text = "See Taskboard-widget PR #5, then https://github.com/VeganMarshmallow/Taskboard-widget/pull/4 and PR #5 again: " +
            "https://github.com/VeganMarshmallow/Taskboard-widget/pull/5"
        assertEquals(listOf(ref(5), ref(4)), parse(text))
    }

    @Test fun newestCommentFirstCappedAndDeduplicated() {
        val comments = listOf(
            "https://github.com/VeganMarshmallow/Taskboard-widget/pull/1",
            "https://github.com/VeganMarshmallow/Taskboard-widget/pull/2",
            (3..9).joinToString(" ") { "https://github.com/VeganMarshmallow/Taskboard-widget/pull/$it" },
            "https://github.com/VeganMarshmallow/Taskboard-widget/pull/2",
        )
        val refs = PrLinks.fromComments(comments, known)
        assertEquals(listOf(2, 3, 4, 5, 6), refs.map { it.number })
        assertEquals(PrLinks.MAX_LINKS, refs.size)
    }

    @Test fun gitSuffixAndHugeInput() {
        assertEquals(listOf(ref(2)), parse("https://github.com/VeganMarshmallow/Taskboard-widget.git/pull/2"))
        val huge = "x".repeat(200_000) + " https://github.com/VeganMarshmallow/Taskboard-widget/pull/2"
        assertEquals(emptyList<PullRef>(), parse(huge)) // beyond the input cap
    }
}
