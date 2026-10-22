package io.github.veganmarshmallow.taskboard.widget

import android.content.Intent

/** What tapping a list row (or its Approve button) asks [RowActionActivity] to do. Carried as fill-in extras. */
sealed class RowAction {
    data class Open(val url: String) : RowAction()
    data class Approve(val issue: Int, val title: String) : RowAction()

    fun toFillIn(): Intent = when (this) {
        is Open -> Intent().putExtra(KIND, OPEN).putExtra(URL, url)
        is Approve -> Intent().putExtra(KIND, APPROVE).putExtra(ISSUE, issue).putExtra(TITLE, title)
    }

    companion object {
        private const val KIND = "row_kind"
        private const val OPEN = "open"
        private const val APPROVE = "approve"
        private const val URL = "row_url"
        private const val ISSUE = "row_issue"
        private const val TITLE = "row_title"
        private const val ALLOWED_PREFIX = "https://github.com/"

        /** Fill-in for rows that do nothing when tapped: [from] returns null, so the trampoline just closes. */
        val NONE: Intent get() = Intent().putExtra(KIND, "none")

        /** Null for anything malformed. Only github.com links are opened. */
        fun from(intent: Intent?): RowAction? = when (intent?.getStringExtra(KIND)) {
            OPEN -> intent.getStringExtra(URL)?.takeIf { it.startsWith(ALLOWED_PREFIX) }?.let(::Open)
            APPROVE -> intent.getIntExtra(ISSUE, 0).takeIf { it > 0 }
                ?.let { Approve(it, intent.getStringExtra(TITLE).orEmpty()) }
            else -> null
        }
    }
}
