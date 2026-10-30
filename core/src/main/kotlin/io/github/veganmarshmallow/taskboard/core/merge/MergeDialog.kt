package io.github.veganmarshmallow.taskboard.core.merge

/** What a merge dialog button does. */
enum class MergeButton { MERGE, CANCEL, CLOSE, OPEN_GITHUB, EDIT_TOKEN, SET_UP }

/**
 * Everything the merge dialog shows, as plain values (task #30). [lines] are shown in order under
 * the [title]; [link] is where [MergeButton.OPEN_GITHUB] or [MergeButton.EDIT_TOKEN] go.
 * [busy] shows a spinner. [kind] lets the UI tint a refusal differently from a question.
 */
data class MergeDialog(
    val kind: Kind,
    val title: String,
    val lines: List<String>,
    val primary: MergeButton?,
    val secondary: MergeButton?,
    val link: String? = null,
    val busy: Boolean = false,
) {
    enum class Kind { PROGRESS, CONFIRM, REFUSAL }
}
