package io.github.veganmarshmallow.taskboard.core.board

import io.github.veganmarshmallow.taskboard.core.github.GitHubRequests.Companion.encode
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.model.Repo

/** Web pages the widget's rows open. */
class BoardLinks(private val repo: Repo = Repo.BOARD) {
    fun issue(number: Int): String = repo.issueUrl(number)

    fun newIssue(): String = "${repo.issuesUrl()}/new"

    fun openIssues(): String = repo.issuesUrl()

    fun recentlyDone(): String = "${repo.issuesUrl()}?q=" + encode("is:issue is:closed label:\"${Labels.DONE}\"")

    /** URL for a tapped row, or null when the row is not tappable (section headers). */
    fun forRow(row: BoardRow): String? = when (row) {
        is BoardRow.Header -> null
        is BoardRow.Item -> issue(row.item.number)
        is BoardRow.More -> openIssues()
        BoardRow.Empty -> newIssue()
        is BoardRow.Done -> recentlyDone()
    }
}
