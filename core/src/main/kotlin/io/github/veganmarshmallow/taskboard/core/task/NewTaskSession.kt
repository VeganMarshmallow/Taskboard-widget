package io.github.veganmarshmallow.taskboard.core.task

import io.github.veganmarshmallow.taskboard.core.http.Outcome

/**
 * The New task form's state, kept outside the Activity so it survives rotation (and closing the
 * form by accident): the [draft], whether a submission is in flight, and a finished result that
 * no screen has shown yet. Not thread-safe except for the in-flight guard; use from one thread.
 */
class NewTaskSession(private val defaultOwner: String = RepoInput.DEFAULT_OWNER) {
    var draft: TaskDraft = TaskDraft.EMPTY

    private val guard = SubmitGuard()
    private var unshown: Outcome<CreatedIssue>? = null

    val isSubmitting: Boolean get() = guard.isBusy

    sealed class Start {
        /** Send [issue]; call [complete] with the outcome. */
        data class Send(val issue: NewIssue) : Start()
        data class Invalid(val errors: Map<TaskDraftValidator.Field, String>) : Start()
        /** A submission is already in flight (e.g. a double tap). Do nothing. */
        data object Busy : Start()
    }

    /** Validates [draft] (after storing it) and, when it is valid, marks a submission as started. */
    fun start(draft: TaskDraft): Start {
        this.draft = draft
        if (guard.isBusy) return Start.Busy
        return when (val r = TaskDraftValidator.validate(draft, defaultOwner)) {
            is TaskDraftValidator.Result.Invalid -> Start.Invalid(r.errors)
            is TaskDraftValidator.Result.Valid -> if (guard.tryStart()) Start.Send(r.issue) else Start.Busy
        }
    }

    /** Ends the submission. A success clears the draft; a failure keeps everything typed. */
    fun complete(result: Outcome<CreatedIssue>) {
        if (result is Outcome.Success) draft = TaskDraft.EMPTY
        unshown = result
        guard.finish()
    }

    /** The finished result not yet shown to the user, once. */
    fun takeResult(): Outcome<CreatedIssue>? = unshown.also { unshown = null }

    /**
     * Drops an unshown success (its toast already told the user) but keeps an unshown failure, so a
     * freshly opened form still explains why the last attempt failed.
     */
    fun dropShownSuccess() {
        if (unshown is Outcome.Success) unshown = null
    }

    /** Forgets the draft (the form's Discard). Ignored while submitting. */
    fun discard() {
        if (!guard.isBusy) draft = TaskDraft.EMPTY
    }
}
