package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.Placement
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.model.Repo
import io.github.veganmarshmallow.taskboard.core.preview.IssueDetails
import io.github.veganmarshmallow.taskboard.core.preview.PreviewScreen
import io.github.veganmarshmallow.taskboard.core.preview.TaskPreview
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetStateCodec
import io.github.veganmarshmallow.taskboard.core.tags.RepoField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where Merge is offered (task #30): board rows, the cached state, and the preview. */
class MergeBoardTest {
    @Test fun mergeIsOfferedOnOpenInReviewTasksOnly() {
        assertTrue(Placement.canMerge(issue(1, Labels.IN_REVIEW)))
        assertTrue("a question about a waiting PR still offers Merge", Placement.canMerge(issue(1, Labels.IN_REVIEW, Labels.NEEDS_OWNER)))
        assertTrue("in-review wins over another status label", Placement.canMerge(issue(1, Labels.IN_REVIEW, Labels.BLOCKED)))
        assertFalse(Placement.canMerge(issue(1, Labels.IN_REVIEW, open = false)))
        assertFalse(Placement.canMerge(issue(1, Labels.IN_REVIEW, pr = true)))
        listOf(Labels.TODO, Labels.IN_PROGRESS, Labels.BLOCKED, Labels.DONE).forEach { assertFalse(it, Placement.canMerge(issue(1, it))) }
        assertFalse(Placement.canMerge(issue(1)))
        assertFalse(Placement.canMerge(issue(1, Labels.NEEDS_OWNER)))
    }

    @Test fun boardRowsCarryMerge() {
        val board = BoardBuilder().build(listOf(issue(2, Labels.IN_REVIEW), issue(3, Labels.NEEDS_OWNER), issue(4, Labels.TODO)), NOW)
        val needsYou = board.sections.first { it.section == Section.NEEDS_YOU }.items
        assertEquals(mapOf(2 to true, 3 to false), needsYou.associate { it.number to it.canMerge })
        val todo = board.sections.first { it.section == Section.TODO }.items.single()
        assertFalse(todo.canMerge)
        assertTrue(todo.canApprove)
    }

    @Test fun mergeSurvivesTheCacheAndOldCachesDecodeWithoutIt() {
        val board = BoardBuilder().build(listOf(issue(2, Labels.IN_REVIEW)), NOW)
        val state = WidgetState(board, null, NOW)
        assertEquals(state, WidgetStateCodec.decode(WidgetStateCodec.encode(state)))
        val old = WidgetStateCodec.encode(state).replace(",\"m\":true", "").replace("\"m\":true,", "")
        assertFalse(old.contains("\"m\""))
        val decoded = WidgetStateCodec.decode(old)
        assertFalse(decoded.board!!.sections.first { it.section == Section.NEEDS_YOU }.items.single().canMerge)
    }

    @Test fun previewOffersMergeFromCacheAndFromTheIssue() {
        val board = BoardBuilder().build(listOf(issue(2, Labels.IN_REVIEW)), NOW)
        assertTrue(TaskPreview.fromBoard(board, 2)!!.canMerge)
        assertTrue(TaskPreview.fromIssue(IssueDetails(issue(2, Labels.IN_REVIEW), "")).canMerge)
        assertFalse(TaskPreview.fromIssue(IssueDetails(issue(2, Labels.TODO), "")).canMerge)
    }

    @Test fun previewScreenMergeLifecycle() {
        val inReview = IssueDetails(issue(2, Labels.IN_REVIEW), "")
        val loading = PreviewScreen.initial(2, TaskPreview.fromIssue(inReview))
        assertFalse("not while loading", loading.showMerge)
        val loaded = loading.issueLoaded(inReview)
        assertTrue(loaded.showMerge)
        assertFalse(loaded.showApprove)
        val merged = loaded.mergeSucceeded()
        assertTrue(merged.justMerged)
        assertFalse(merged.showMerge)
        // The reload right after still says in-review (Claude closes it later): Merge stays hidden.
        assertFalse(merged.loadStarted().issueLoaded(inReview).showMerge)
        assertFalse(PreviewScreen.initial(2, TaskPreview.fromIssue(inReview), mergedEarlier = true).issueLoaded(inReview).showMerge)
        assertFalse(loaded.issueFailed(BoardError.IssueGone(2)).showMerge)
        assertTrue(loaded.issueFailed(BoardError.Network).showMerge)
    }

    @Test fun repoFieldWithOwner() {
        assertEquals(Repo("VeganMarshmallow", "Taskboard-widget"), RepoField.repo("### Repo\n\nVeganMarshmallow/Taskboard-widget"))
        assertEquals(Repo("VeganMarshmallow", "site"), RepoField.repo("Do it.\n\nRepo: https://github.com/VeganMarshmallow/site.git"))
        assertNull(RepoField.repo("### Repo\n\n_No response_"))
        assertNull(RepoField.repo("### Repo\n\nsite"))
        assertNull(RepoField.repo(null))
        assertEquals("site", RepoField.name("Repo: VeganMarshmallow/site"))
    }
}
