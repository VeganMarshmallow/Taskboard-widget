package io.github.veganmarshmallow.taskboard.core.questions

import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.preview.IssueDetails
import java.time.Duration

/** The Questions screen's reads (task #82), through [GitHubApi]'s request pipeline. */
class QuestionsApi(private val api: GitHubApi) {
    /** Open tasks plus tasks done within [doneWindow] (a general question Claude answered and closed), with bodies. */
    fun fetchTasks(doneWindow: Duration = Duration.ofDays(7)): Outcome<List<IssueDetails>> =
        api.fetchPages(api.requests.openIssues(), TASK_PAGES, QuestionsJsonParser::parseIssues).flatMap { open ->
            api.fetchPages(api.requests.recentlyDone(api.clock.instant().minus(doneWindow)), 1, QuestionsJsonParser::parseIssues)
                .map { done ->
                    val openNumbers = open.mapTo(HashSet()) { it.issue.number }
                    open + done.filter { it.issue.number !in openNumbers }
                }
        }

    /** Every comment made in the board repo within [window], newest first, up to [COMMENT_PAGES] pages. */
    fun fetchRecentComments(window: Duration = COMMENT_WINDOW): Outcome<List<TaskComment>> =
        api.fetchPages(api.requests.repoComments(api.clock.instant().minus(window)), COMMENT_PAGES, QuestionsJsonParser::parseRepoComments)

    companion object {
        const val TASK_PAGES = 10
        const val COMMENT_PAGES = 5
        val COMMENT_WINDOW: Duration = Duration.ofDays(30)
    }
}
