package io.github.veganmarshmallow.taskboard.widget

import android.content.Intent

/** What tapping a list row (or its Approve button) asks [RowActionActivity] to do. Carried as fill-in extras. */
sealed class RowAction {
    data class Open(val url: String) : RowAction()
    data class Approve(val issue: Int, val title: String) : RowAction()
    /** Opens the task preview over the home screen. */
    data class Preview(val issue: Int) : RowAction()
    /**
     * Finds, checks and (after confirmation) merges the task's PR (task #30). [mergeFirst]: the
     * title of the task whose PR is to be merged before this one (#45); the tap then only says so.
     */
    data class Merge(val issue: Int, val mergeFirst: String? = null) : RowAction()

    fun toFillIn(): Intent = when (this) {
        is Open -> Intent().putExtra(KIND, OPEN).putExtra(URL, url)
        is Approve -> Intent().putExtra(KIND, APPROVE).putExtra(ISSUE, issue).putExtra(TITLE, title)
        is Preview -> Intent().putExtra(KIND, PREVIEW).putExtra(ISSUE, issue)
        is Merge -> Intent().putExtra(KIND, MERGE).putExtra(ISSUE, issue).putExtra(FIRST, mergeFirst)
    }

    companion object {
        private const val KIND = "row_kind"
        private const val OPEN = "open"
        private const val APPROVE = "approve"
        private const val PREVIEW = "preview"
        private const val MERGE = "merge"
        private const val URL = "row_url"
        private const val ISSUE = "row_issue"
        private const val TITLE = "row_title"
        private const val FIRST = "row_merge_first"
        private const val ALLOWED_PREFIX = "https://github.com/"

        /** Fill-in for rows that do nothing when tapped: [from] returns null, so the trampoline just closes. */
        val NONE: Intent get() = Intent().putExtra(KIND, "none")

        /** Null for anything malformed. Only github.com links are opened. */
        fun from(intent: Intent?): RowAction? = when (intent?.getStringExtra(KIND)) {
            OPEN -> intent.getStringExtra(URL)?.takeIf { it.startsWith(ALLOWED_PREFIX) }?.let(::Open)
            APPROVE -> intent.getIntExtra(ISSUE, 0).takeIf { it > 0 }
                ?.let { Approve(it, intent.getStringExtra(TITLE).orEmpty()) }
            PREVIEW -> intent.getIntExtra(ISSUE, 0).takeIf { it > 0 }?.let(::Preview)
            MERGE -> intent.getIntExtra(ISSUE, 0).takeIf { it > 0 }?.let { Merge(it, intent.getStringExtra(FIRST)?.takeIf(String::isNotBlank)) }
            else -> null
        }
    }
}
