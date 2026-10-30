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

    /** The request finished, either way: the marker goes; a success also drops the draft. */
    fun completed(result: Outcome<*>) {
        store.marker = null
        if (result is Outcome.Success) store.draft = null
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
            is SubmitRecovery.Result.Created -> {
                store.marker = null
                store.draft = null
            }
            is SubmitRecovery.Result.NotCreated, is SubmitRecovery.Result.TooOld -> store.marker = null
            is SubmitRecovery.Result.CheckFailed -> Unit
        }
    }
}
