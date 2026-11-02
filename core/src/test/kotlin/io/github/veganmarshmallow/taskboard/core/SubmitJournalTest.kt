package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.model.Priority
import io.github.veganmarshmallow.taskboard.core.task.CreatedIssue
import io.github.veganmarshmallow.taskboard.core.task.NewIssue
import io.github.veganmarshmallow.taskboard.core.task.NewTaskStore
import io.github.veganmarshmallow.taskboard.core.task.SubmitJournal
import io.github.veganmarshmallow.taskboard.core.task.SubmitMarker
import io.github.veganmarshmallow.taskboard.core.task.SubmitMarkerCodec
import io.github.veganmarshmallow.taskboard.core.task.SubmitRecovery.Result
import io.github.veganmarshmallow.taskboard.core.task.TaskDraft
import io.github.veganmarshmallow.taskboard.core.task.TaskDraftCodec
import io.github.veganmarshmallow.taskboard.core.task.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class SubmitJournalTest {
    private class MemoryStore : NewTaskStore {
        override var draft: String? = null
        override var marker: String? = null
    }

    private val store = MemoryStore()
    private val journal = SubmitJournal(store, FIXED_CLOCK)
    private val draft = TaskDraft("Fix \"it\"\n", "goal ✓ ‮", "done", "o/r", TaskType.RESEARCH, Priority.LOW, approveNow = true)
    private val issue = NewIssue("Fix \"it\"", "body", emptyList())

    /** What a new process sees: a fresh journal on the same disk. */
    private fun afterProcessDeath() = SubmitJournal(store, FIXED_CLOCK)

    @Test fun draftRoundTripsThroughProcessDeath() {
        journal.saveDraft(draft)
        assertEquals(draft, afterProcessDeath().savedDraft())
    }

    @Test fun emptyDraftClearsTheSavedOne() {
        journal.saveDraft(draft)
        journal.saveDraft(TaskDraft.EMPTY)
        assertNull(store.draft)
        assertEquals(TaskDraft.EMPTY, journal.savedDraft())
    }

    @Test fun deathMidSubmitLeavesDraftAndMarker() {
        journal.sending(draft, issue)
        val next = afterProcessDeath()
        assertEquals(draft, next.savedDraft())
        assertEquals(SubmitMarker(issue.title, NOW), next.orphanedMarker(submittingHere = false))
    }

    @Test fun liveSubmitInThisProcessIsNotAnOrphan() {
        journal.sending(draft, issue)
        assertNull(journal.orphanedMarker(submittingHere = true))
        assertNotNull(store.marker)
    }

    @Test fun successClearsMarkerAndDraft() {
        journal.sending(draft, issue)
        journal.completed(Outcome.Success(CreatedIssue(5)))
        assertNull(store.marker)
        assertNull(store.draft)
    }

    @Test fun definiteFailureClearsMarkerButKeepsDraft() {
        listOf(BoardError.BadToken(401), BoardError.NoAccess, BoardError.NoToken, BoardError.Unexpected("HTTP 422: x")).forEach { error ->
            journal.sending(draft, issue)
            journal.completed(Outcome.Failure(error))
            assertNull("$error", store.marker)
            assertEquals("$error", draft, journal.savedDraft())
        }
    }

    @Test fun failureThatMayHaveCreatedTheIssueKeepsTheMarkerForACheck() {
        listOf(BoardError.Timeout, BoardError.Network, BoardError.Unreachable, BoardError.Server(502)).forEach { error ->
            store.marker = null
            journal.sending(draft, issue)
            journal.completed(Outcome.Failure(error))
            assertEquals("$error", SubmitMarker(issue.title, NOW), journal.orphanedMarker(submittingHere = false))
            assertEquals("$error", draft, journal.savedDraft())
        }
    }

    @Test fun discardForgetsDraftAndMarker() {
        journal.sending(draft, issue)
        journal.completed(Outcome.Failure(BoardError.Timeout))
        journal.discarded()
        assertNull(store.draft)
        assertNull(store.marker)
    }

    /** Each write is its own commit; a death between two must never leave a sent draft without a marker. */
    @Test fun draftIsWrittenBeforeAndClearedBeforeTheMarker() {
        val writes = mutableListOf<String>()
        val recording = object : NewTaskStore {
            override var draft: String? = null
                set(value) { field = value; writes += if (value == null) "draft-" else "draft+" }
            override var marker: String? = null
                set(value) { field = value; writes += if (value == null) "marker-" else "marker+" }
        }
        val j = SubmitJournal(recording, FIXED_CLOCK)
        j.sending(draft, issue)
        j.completed(Outcome.Success(CreatedIssue(5)))
        assertEquals(listOf("draft+", "marker+", "draft-", "marker-"), writes)
        writes.clear()
        j.sending(draft, issue)
        j.recovered(Result.Created(issue.title, 5))
        assertEquals(listOf("draft+", "marker+", "draft-", "marker-"), writes)
    }

    @Test fun markerWithoutDraftIsStillCheckedAndDraftWithoutMarkerIsJustADraft() {
        store.marker = SubmitMarkerCodec.encode(SubmitMarker("Lost draft", NOW))
        assertEquals(SubmitMarker("Lost draft", NOW), journal.orphanedMarker(false))
        assertEquals(TaskDraft.EMPTY, journal.savedDraft())
        store.marker = null
        journal.saveDraft(draft)
        assertNull(journal.orphanedMarker(false))
        assertEquals(draft, journal.savedDraft())
    }

    @Test fun unicodeAndQuotesInTheTitleSurviveTheMarker() {
        val marker = SubmitMarker("תיקון \"באג\" 🐛 \\ ‮", NOW)
        assertEquals(marker, SubmitMarkerCodec.decode(SubmitMarkerCodec.encode(marker)))
    }

    @Test fun recoveryResultsUpdateTheStore() {
        fun prime() { journal.sending(draft, issue) }
        prime(); journal.recovered(Result.Created(issue.title, 4))
        assertNull(store.marker); assertNull(store.draft)
        prime(); journal.recovered(Result.NotCreated(issue.title))
        assertNull(store.marker); assertEquals(draft, journal.savedDraft())
        prime(); journal.recovered(Result.TooOld(issue.title))
        assertNull(store.marker); assertEquals(draft, journal.savedDraft())
        prime(); journal.recovered(Result.CheckFailed(issue.title, BoardError.Network))
        assertNotNull("kept, to check again next time", journal.orphanedMarker(false)); assertEquals(draft, journal.savedDraft())
    }

    @Test fun unreadableMarkerIsDropped() {
        store.marker = "garbage"
        assertNull(journal.orphanedMarker(false))
        assertNull(store.marker)
    }

    @Test fun nothingStoredMeansNothingToRecover() {
        assertNull(journal.orphanedMarker(false))
        assertEquals(TaskDraft.EMPTY, journal.savedDraft())
    }

    @Test fun markerCodecRejectsMalformedText() {
        val good = SubmitMarker("t", Instant.ofEpochMilli(1_700_000_000_123))
        assertEquals(good, SubmitMarkerCodec.decode(SubmitMarkerCodec.encode(good)))
        listOf(
            null, "", "  ", "[]", "{", "{}", """{"title":"t"}""", """{"startedAt":1}""", """{"title":" ","startedAt":1}""",
            """{"title":"t","startedAt":"1"}""", """{"title":"t","startedAt":1.5}""", """{"title":3,"startedAt":1}""",
        ).forEach { assertNull(it, SubmitMarkerCodec.decode(it)) }
    }

    @Test fun draftCodecFallsBackPerField() {
        assertNull(TaskDraftCodec.decode(null))
        assertNull(TaskDraftCodec.decode("not json"))
        assertNull(TaskDraftCodec.decode("[1]"))
        assertEquals(TaskDraft.EMPTY, TaskDraftCodec.decode("{}"))
        assertEquals(
            TaskDraft(title = "t", type = TaskType.FEATURE, priority = Priority.NORMAL),
            TaskDraftCodec.decode("""{"title":"t","goal":5,"type":"epic","priority":"urgent","approveNow":"yes"}"""),
        )
    }

    @Test fun hugeDraftRoundTrips() {
        val big = TaskDraft(title = "x".repeat(300), goal = "line\n".repeat(20_000))
        journal.saveDraft(big)
        assertEquals(big, journal.savedDraft())
    }
}
