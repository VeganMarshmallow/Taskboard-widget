package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.error.ErrorMessages
import io.github.veganmarshmallow.taskboard.core.merge.MergeButton
import io.github.veganmarshmallow.taskboard.core.merge.MergeDialog
import io.github.veganmarshmallow.taskboard.core.merge.MergeDialogs
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.model.Repo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeDialogsTest {
    private val dialogs = MergeDialogs(ErrorMessages { "14:05" })
    private fun refusal(problem: MergeProblem) = dialogs.refusal(problem, 30)

    @Test fun confirmShowsTitleRepoAndNumber() {
        val d = dialogs.confirm(pr(12, title = "Widget: merge from the widget"))
        assertEquals(MergeDialog.Kind.CONFIRM, d.kind)
        assertEquals("Merge PR #12?", d.title)
        assertEquals("Widget: merge from the widget", d.lines[0])
        assertEquals("VeganMarshmallow/Taskboard-widget #12", d.lines[1])
        assertTrue(d.lines[2].startsWith("Into main"))
        assertEquals(MergeButton.MERGE, d.primary)
        assertEquals(MergeButton.CANCEL, d.secondary)
        assertEquals("https://github.com/VeganMarshmallow/Taskboard-widget/pull/12", d.link)
    }

    @Test fun confirmCleansHostileTitles() {
        val d = dialogs.confirm(pr(12, title = "‮evil\nline  two"))
        assertEquals("evil line two", d.lines[0])
        assertEquals("(untitled PR)", dialogs.confirm(pr(1, title = "  ")).lines[0])
        assertTrue(dialogs.confirm(pr(1, title = "א".repeat(1000))).lines[0].length <= 200)
    }

    @Test fun progressDialogs() {
        assertTrue(dialogs.checking(30).busy)
        assertEquals(MergeButton.CANCEL, dialogs.checking(30).secondary)
        val merging = dialogs.merging(pr(12))
        assertTrue(merging.busy)
        assertEquals(null, merging.primary)
        assertEquals(null, merging.secondary) // a merge in flight can't be cancelled
    }

    @Test fun stackedSaysWhichPrToMergeFirst() {
        val d = refusal(MergeProblem.Stacked(pr(4, base = "claude/29-first"), blocker = 3))
        assertEquals("This PR is stacked on claude/29-first; merge #3 first.", d.lines[0])
        assertEquals(MergeButton.OPEN_GITHUB, d.primary)
        assertEquals("https://github.com/VeganMarshmallow/Taskboard-widget/pull/4", d.link)
        val unknown = refusal(MergeProblem.Stacked(pr(4, base = "feature"), blocker = null))
        assertEquals("This PR is stacked on feature; merge that branch's PR first.", unknown.lines[0])
    }

    @Test fun requiredTitles() {
        assertEquals("Has conflicts", refusal(MergeProblem.Conflicts(pr(1))).title)
        assertEquals("Checks still running", refusal(MergeProblem.ChecksRunning(pr(1), listOf("emulator"))).title)
        assertEquals("Checks failed", refusal(MergeProblem.ChecksFailed(pr(1), listOf("lint"))).title)
        assertEquals("PR changed; check again", refusal(MergeProblem.HeadChanged(pr(1))).title)
        assertEquals("Already merged", refusal(MergeProblem.AlreadyMerged(pr(1))).title)
        assertEquals("Token can't merge here", refusal(MergeProblem.NoRepoAccess(WIDGET)).title)
    }

    @Test fun conflictsIncludeGitHubsMessage() {
        val d = refusal(MergeProblem.Conflicts(pr(1), "Pull Request is not mergeable"))
        assertEquals(listOf("PR #1 can't be merged as it is.", "GitHub says: Pull Request is not mergeable"), d.lines)
    }

    @Test fun checkNamesAreListedAndCapped() {
        assertEquals("Wait for check emulator to finish, then try again.", refusal(MergeProblem.ChecksRunning(pr(1), listOf("emulator"))).lines[0])
        val many = refusal(MergeProblem.ChecksFailed(pr(1), listOf("a", "b", "c", "d", "e"))).lines[0]
        assertEquals("Checks a, b, c and 2 more failed on PR #1.", many)
    }

    @Test fun tokenStepsArePrecise() {
        val d = refusal(MergeProblem.NoRepoAccess(Repo("VeganMarshmallow", "site")))
        val text = d.lines.joinToString("\n")
        assertTrue(text.contains("VeganMarshmallow/site"))
        assertTrue(text.contains("Repository access: add site"))
        assertTrue(text.contains("Pull requests: Read and write"))
        assertTrue(text.contains("Contents: Read and write"))
        assertTrue(text.contains("Checks: Read"))
        assertTrue(text.contains("Commit statuses: Read"))
        assertEquals(MergeButton.EDIT_TOKEN, d.primary)
        assertEquals(MergeDialogs.TOKEN_SETTINGS, d.link)
    }

    @Test fun boardErrorsReuseTheirMessages() {
        val network = refusal(MergeProblem.Failed(BoardError.Network))
        assertEquals(listOf("No network: can't reach GitHub"), network.lines)
        assertEquals(MergeButton.OPEN_GITHUB, network.primary)
        assertEquals(MergeButton.SET_UP, refusal(MergeProblem.Failed(BoardError.BadToken(401))).primary)
        assertTrue(refusal(MergeProblem.Failed(BoardError.RateLimited(null))).lines[0].startsWith("Rate-limited"))
    }

    @Test fun notFoundAndAmbiguousLinkSomewhereUseful() {
        val nf = refusal(MergeProblem.NotFound)
        assertEquals("https://github.com/VeganMarshmallow/claude-workspace/issues/30", nf.link)
        assertTrue(nf.lines[0].contains("Closes VeganMarshmallow/claude-workspace#30"))
        val amb = refusal(MergeProblem.Ambiguous(listOf(pr(7), pr(9))))
        assertTrue(amb.lines[0].contains("Taskboard-widget #7, Taskboard-widget #9"))
        assertEquals(MergeButton.OPEN_GITHUB, amb.primary)
    }

    @Test fun everyRefusalHasACloseButton() {
        listOf(
            MergeProblem.NotFound, MergeProblem.Ambiguous(listOf(pr(1))), MergeProblem.AlreadyMerged(pr(1)), MergeProblem.Closed(pr(1)),
            MergeProblem.Draft(pr(1)), MergeProblem.Stacked(pr(1, base = "x"), null), MergeProblem.Conflicts(pr(1)),
            MergeProblem.MergeabilityUnknown(pr(1)), MergeProblem.ChecksRunning(pr(1), listOf("a")),
            MergeProblem.ChecksFailed(pr(1), listOf("a")), MergeProblem.NoChecks(pr(1)), MergeProblem.HeadChanged(pr(1)),
            MergeProblem.NoRepoAccess(WIDGET), MergeProblem.Failed(BoardError.Timeout),
        ).forEach { p ->
            val d = refusal(p)
            assertEquals("$p", MergeDialog.Kind.REFUSAL, d.kind)
            assertEquals("$p", MergeButton.CLOSE, d.secondary)
            assertTrue("$p", d.link!!.startsWith("https://github.com/"))
            assertTrue("$p", d.lines.isNotEmpty() && d.title.isNotEmpty())
        }
    }

    @Test fun anUnclearMergeResultSaysToCheckGitHub() {
        val timeout = dialogs.mergeFailed(MergeProblem.Failed(BoardError.Timeout), 30)
        assertTrue(timeout.lines.last().startsWith("It may have merged anyway"))
        assertTrue(dialogs.mergeFailed(MergeProblem.Failed(BoardError.Server(502)), 30).lines.last().startsWith("It may have merged"))
        assertEquals(refusal(MergeProblem.HeadChanged(pr(1))), dialogs.mergeFailed(MergeProblem.HeadChanged(pr(1)), 30))
        assertEquals(refusal(MergeProblem.Failed(BoardError.BadToken(401))), dialogs.mergeFailed(MergeProblem.Failed(BoardError.BadToken(401)), 30))
    }

    @Test fun mergedToast() {
        assertEquals(
            "Merged Taskboard-widget PR #12 into main. Claude closes task #30 on its next run",
            dialogs.merged(pr(12), 30),
        )
    }
}
