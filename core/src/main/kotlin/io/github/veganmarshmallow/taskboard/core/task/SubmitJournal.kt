package io.github.veganmarshmallow.taskboard.core.task

import io.github.veganmarshmallow.taskboard.core.http.Outcome
import java.time.Clock

/** Where the New task form's draft and submit marker are kept (SharedPreferences on Android). Writes must be synchronous. */
interface NewTaskStore {
    var draft: String?
    var marker: String?
}

/**
 * What the New task form writes to disk, and when, so process death loses no typed text and never
 * hides a submit: the draft is saved on pause and before sending; a [SubmitMarker] is saved before
 * sending and cleared when the request completes; a success also clears the draft.
 *
 * Write order matters, since each write is its own commit and the process may die between two:
 * the draft is saved before the marker, and cleared before it. So a death between the two writes
 * leaves either a draft that was never sent, or a marker that the next check resolves; never a
 * sent draft without a marker (which would invite a silent duplicate).
 */
class SubmitJournal(private val store: NewTaskStore, private val clock: Clock) {
    /** The draft a previous process saved, or [TaskDraft.EMPTY]. */
    fun savedDraft(): TaskDraft = TaskDraftCodec.decode(store.draft) ?: TaskDraft.EMPTY

    /** Saves [draft]; an empty draft clears the saved one. */
    fun saveDraft(draft: TaskDraft) {
        store.draft = if (draft == TaskDraft.EMPTY) null else TaskDraftCodec.encode(draft)
    }

    /** Before the POST: the draft (so a death mid-submit keeps it) and the marker. */
    fun sending(draft: TaskDraft, issue: NewIssue) {
        saveDraft(draft)
        store.marker = SubmitMarkerCodec.encode(SubmitMarker(issue.title, clock.instant()))
    }

    /**
     * The request finished. A success drops the draft, then the marker. A definite failure drops
     * the marker. A failure after which the issue may still exist ([CreateErrors.mayAlreadyExist]:
     * timeout, dropped connection, 5xx) keeps the marker, so the next form open checks GitHub
     * instead of letting a retry make a duplicate.
     */
    fun completed(result: Outcome<*>) {
        when {
            result is Outcome.Success -> created()
            result is Outcome.Failure && CreateErrors.mayAlreadyExist(result.error) -> Unit
            else -> store.marker = null
        }
    }

    /** The form's Discard: the draft goes, and so does a marker (the user was told to check the board). */
    fun discarded() {
        store.draft = null
        store.marker = null
    }

    private fun created() {
        store.draft = null
        store.marker = null
    }

    /**
     * A marker left by a process that died mid-submit. Null while this process's own submit is in
     * flight ([submittingHere]: that marker is live). An unreadable marker is cleared.
     */
    fun orphanedMarker(submittingHere: Boolean): SubmitMarker? {
        if (submittingHere) return null
        val text = store.marker ?: return null
        return SubmitMarkerCodec.decode(text).also { if (it == null) store.marker = null }
    }

    /** Applies a check's [result]: created drops marker and draft; known-not-created or too old drops the marker; a failed check keeps it to try again. */
    fun recovered(result: SubmitRecovery.Result) {
        when (result) {
            is SubmitRecovery.Result.Created -> created()
            is SubmitRecovery.Result.NotCreated, is SubmitRecovery.Result.TooOld -> store.marker = null
            is SubmitRecovery.Result.CheckFailed -> Unit
        }
    }
}
