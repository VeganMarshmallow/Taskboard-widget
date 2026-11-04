package io.github.veganmarshmallow.taskboard.core.board

import io.github.veganmarshmallow.taskboard.core.http.QueryEncoding.encode
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.model.Repo

/** Web pages the widget's rows open. */
class BoardLinks(private val repo: Repo = Repo.BOARD) {
    fun issue(number: Int): String = repo.issueUrl(number)

    fun newIssue(): String = "${repo.issuesUrl()}/new"

    fun openIssues(): String = repo.issuesUrl()

    fun recentlyDone(): String = "${repo.issuesUrl()}?q=" + encode("is:issue is:closed label:\"${Labels.DONE}\"")

    /**
     * URL for a tapped row, or null when the row is not tappable. Only an empty Todo invites a new
     * task, and not when a filter (#59) emptied it: the tab has tasks, they are just hidden.
     */
    fun forRow(row: BoardRow): String? = when (row) {
        is BoardRow.Item -> issue(row.item.number)
        is BoardRow.More -> if (row.section == Section.DONE) recentlyDone() else openIssues()
        is BoardRow.Empty -> if (row.section == Section.TODO && !row.filtered) newIssue() else null
    }
}
