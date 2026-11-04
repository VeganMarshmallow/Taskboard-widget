package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.board.MergeLane
import io.github.veganmarshmallow.taskboard.core.board.MergeWait
import io.github.veganmarshmallow.taskboard.core.board.withMergeLanes
import io.github.veganmarshmallow.taskboard.core.error.ErrorMessages
import io.github.veganmarshmallow.taskboard.core.merge.MergeButton
import io.github.veganmarshmallow.taskboard.core.merge.MergeDialogs
import io.github.veganmarshmallow.taskboard.core.merge.PullRef
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllBanner
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllEnd
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllPhase
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllState
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStateCodec
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStop
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllText
import io.github.veganmarshmallow.taskboard.core.mergeall.MergedPr
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.work.BoardJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** A Merge all run on disk, its job, and what the widget and dialog say about it (#57). */
class MergeAllStateTest {
    private val now = Instant.parse("2026-11-03T10:00:00Z")
    private val text = MergeAllText(MergeDialogs(ErrorMessages { it.toString() }))
    private val run = MergeAllState.start("run-1", planned(31, 34, 32), now)

    // --- codec ---

    @Test fun everyStateRoundTrips() {
        val states = listOf(
            run,
            run.copy(index = 1, phase = MergeAllPhase.WAIT, merged = listOf(MergedPr(PullRef(WIDGET, 31), "claude/31")), oldHead = sha('1'), waitSince = now),
            run.copy(phase = MergeAllPhase.MERGE, checkedHead = sha('2')),
            run.copy(end = MergeAllEnd.Done),
            run.copy(end = MergeAllEnd.Cancelled),
            run.copy(end = MergeAllEnd.Stopped(2, MergeAllStop(MergeAllStop.Kind.CHECKS_FAILED, "build, \"lint\""))),
            run.copy(end = MergeAllEnd.Stopped(0, MergeAllStop(MergeAllStop.Kind.NETWORK))),
        )
        states.forEach { assertEquals(it, MergeAllStateCodec.decode(MergeAllStateCodec.encode(it))) }
        MergeAllStop.Kind.entries.forEach { kind ->
            val s = run.copy(end = MergeAllEnd.Stopped(0, MergeAllStop(kind)))
            assertEquals(s, MergeAllStateCodec.decode(MergeAllStateCodec.encode(s)))
        }
    }

    @Test fun anythingUnreadableIsNoRun() {
        val good = MergeAllStateCodec.encode(run)
        listOf(
            null, "", "not json", "[]", "{}",
            good.replace("\"v\":1", "\"v\":2"),
            good.replace("\"PREPARE\"", "\"SOMETHING\""),
            good.replace("\"index\":0", "\"index\":7"),
            MergeAllStateCodec.encode(run.copy(end = MergeAllEnd.Stopped(0, MergeAllStop(MergeAllStop.Kind.NETWORK)))).replace("\"at\":0", "\"at\":3"),
            good.replace("\"merged\":[]", "\"merged\":[{}]"),
            good.replace("\"pr\":31", "\"pr\":-1"),
            good.replace("\"plan\":[", "\"plan\":[7,"),
        ).forEach { assertNull("decoded: $it", MergeAllStateCodec.decode(it)) }
    }

    @Test fun aNewRunStartsAtItsFirstPr() {
        assertEquals(0, run.index)
        assertEquals(MergeAllPhase.PREPARE, run.phase)
        assertTrue(run.running)
    }

    // --- job ---

    @Test fun theJobRoundTripsThroughItsExtras() {
        listOf(BoardJob.MergeAll(), BoardJob.MergeAll(Duration.ofSeconds(30))).forEach { job ->
            val extras = job.toExtras()
            assertEquals(job, BoardJob.fromExtras(extras::get))
            assertEquals(BoardJob.MERGE_ALL_ID, job.jobId)
            assertTrue(job.needsNetwork && job.persisted && !job.urgent)
        }
        val malformed = mapOf("kind" to "merge_all", "delayMs" to "-5")
        assertNull(BoardJob.fromExtras(malformed::get))
    }

    // --- words ---

    @Test fun progressNamesThePrNumberAndStep() {
        assertEquals("Merging 1 of 3… updating", text.progress(run, cancelling = false))
        assertEquals("Merging 2 of 3… waiting for checks", text.progress(run.copy(index = 1, phase = MergeAllPhase.WAIT), false))
        assertEquals("Merging 3 of 3… merging · stopping after this step", text.progress(run.copy(index = 2, phase = MergeAllPhase.MERGE), true))
    }

    @Test fun resultsSayWhichPrStoppedItAndWhy() {
        val one = listOf(MergedPr(PullRef(WIDGET, 31), "claude/31"))
        assertEquals("Merged all 3 PRs", text.result(run.copy(end = MergeAllEnd.Done)))
        assertEquals("Merge all cancelled: merged 1 of 3", text.result(run.copy(merged = one, end = MergeAllEnd.Cancelled)))
        assertEquals(
            "Stopped at Taskboard-widget #34: checks failed (build). Merged 1 of 3",
            text.result(run.copy(merged = one, end = MergeAllEnd.Stopped(1, MergeAllStop(MergeAllStop.Kind.CHECKS_FAILED, "build")))),
        )
        assertNull(text.result(run))
        MergeAllStop.Kind.entries.forEach { assertTrue(it.name, text.reason(MergeAllStop(it)).isNotBlank()) }
        assertEquals("checks waiting for approval on GitHub (build)", text.reason(MergeAllStop(MergeAllStop.Kind.CHECKS_APPROVAL, "build")))
    }

    @Test fun theConfirmationListsThePlanInOrder() {
        val dialog = text.confirm(planned(31, 34))
        assertEquals("Merge 2 PRs?", dialog.title)
        assertEquals("Merge 2 PRs in this order:", dialog.lines[0])
        assertEquals("1. Taskboard-widget #31: Task 131", dialog.lines[1])
        assertEquals("2. Taskboard-widget #34: Task 134", dialog.lines[2])
        assertEquals(MergeButton.MERGE_ALL, dialog.primary)
        assertEquals(MergeButton.CANCEL, dialog.secondary)
    }

    // --- the widget's line ---

    private val board = BoardBuilder().build(
        listOf(Issue(30, "A", setOf(Labels.IN_REVIEW), true), Issue(31, "B", setOf(Labels.IN_REVIEW), true)), now,
    ).withMergeLanes(mapOf(30 to MergeLane.NEXT, 31 to MergeLane(false, MergeWait.QUEUED, "A")))

    @Test fun theLineOffersMergeAllOnNeedsYouOnly() {
        assertEquals(MergeAllBanner("2 PRs ready to merge", MergeAllBanner.Button.MERGE_ALL), MergeAllBanner.of(null, false, board, true, text))
        assertNull(MergeAllBanner.of(null, false, board, onNeedsYou = false, text = text))
    }

    @Test fun aRunShowsOnEveryTabWithCancelThenOk() {
        assertEquals(MergeAllBanner("Merging 1 of 3… updating", MergeAllBanner.Button.CANCEL), MergeAllBanner.of(run, false, board, false, text))
        assertEquals(MergeAllBanner.Button.CANCEL, MergeAllBanner.of(run, false, null, true, text)!!.button)
        assertNull("Cancel was tapped: no second Cancel", MergeAllBanner.of(run, true, board, true, text)!!.button)
        val done = MergeAllBanner.of(run.copy(end = MergeAllEnd.Done), false, board, true, text)
        assertEquals(MergeAllBanner("Merged all 3 PRs", MergeAllBanner.Button.DISMISS), done)
    }
}
