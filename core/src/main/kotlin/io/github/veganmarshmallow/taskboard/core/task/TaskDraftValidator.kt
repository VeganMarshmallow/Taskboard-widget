package io.github.veganmarshmallow.taskboard.core.task

/**
 * Turns a [TaskDraft] into a [NewIssue], or field-by-field errors to show next to each field.
 * Titles are trimmed (a title of only invisible bidi marks counts as empty), inner line breaks and tabs become spaces, and they may be at most
 * [MAX_TITLE] characters (GitHub's limit, counted in code points so Hebrew and emoji count as one
 * each). The whole body must fit GitHub's [MAX_BODY] limit.
 */
object TaskDraftValidator {
    const val MAX_TITLE = 256
    const val MAX_BODY = 65_536

    enum class Field { TITLE, GOAL, REPO }

    sealed class Result {
        data class Valid(val issue: NewIssue) : Result()
        data class Invalid(val errors: Map<Field, String>) : Result()
    }

    private val TITLE_BREAKS = Regex("[\\r\\n\\t]+")

    fun validate(draft: TaskDraft, defaultOwner: String = RepoInput.DEFAULT_OWNER): Result {
        val errors = LinkedHashMap<Field, String>()
        val title = cleanTitle(draft.title)
        val titleLength = title.codePointCount(0, title.length)
        when {
            InvisibleText.isBlank(title) -> errors[Field.TITLE] = "A title is required"
            titleLength > MAX_TITLE -> errors[Field.TITLE] = "Title is too long ($titleLength of $MAX_TITLE characters)"
        }
        val repo = when (val r = RepoInput.parse(draft.repo, defaultOwner)) {
            RepoInput.Result.Empty -> null
            is RepoInput.Result.Valid -> r.repo
            is RepoInput.Result.Invalid -> { errors[Field.REPO] = r.reason; null }
        }
        val body = IssueBodyFormat.build(repo, draft.type, draft.goal, draft.doneWhen)
        if (body.length > MAX_BODY) {
            errors[Field.GOAL] = "Text is too long: ${body.length} of $MAX_BODY characters"
        }
        if (errors.isNotEmpty()) return Result.Invalid(errors)
        return Result.Valid(NewIssue(title, body, TaskLabels.of(draft.type, draft.priority, draft.approveNow)))
    }

    fun cleanTitle(raw: String): String = raw.replace(TITLE_BREAKS, " ").trim()
}
