package io.github.veganmarshmallow.taskboard.core.questions

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.github.MalformedResponseException
import io.github.veganmarshmallow.taskboard.core.github.PagedFetcher
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.preview.IssueDetails
import java.time.Duration

/** The Questions screen's reads (task #82), through [GitHubApi]'s request pipeline. */
class QuestionsApi(private val api: GitHubApi) {
    /** Open tasks plus tasks done within [doneWindow] (a general question Claude answered and closed), with bodies. */
    fun fetchTasks(doneWindow: Duration = Duration.ofDays(7)): Outcome<List<IssueDetails>> =
        fetchAll(api.requests.openIssues(), TASK_PAGES, QuestionsJsonParser::parseIssues).flatMap { open ->
            fetchAll(api.requests.recentlyDone(api.clock.instant().minus(doneWindow)), 1, QuestionsJsonParser::parseIssues)
                .map { done -> open + done.filter { d -> open.none { it.issue.number == d.issue.number } } }
        }

    /** Every comment made in the board repo within [window], newest first, up to [COMMENT_PAGES] pages. */
    fun fetchRecentComments(window: Duration = COMMENT_WINDOW): Outcome<List<TaskComment>> =
        fetchAll(api.requests.repoComments(api.clock.instant().minus(window)), COMMENT_PAGES, QuestionsJsonParser::parseRepoComments)

    private fun <T> fetchAll(first: HttpRequest, pages: Int, parse: (String) -> List<T>): Outcome<List<T>> =
        PagedFetcher(pages, api.requests::page) { request: HttpRequest ->
            api.send(request).flatMap { response ->
                try {
                    Outcome.Success(PagedFetcher.Page(parse(response.body), response.header("link")))
                } catch (e: MalformedResponseException) {
                    Outcome.Failure(BoardError.Unexpected(e.message ?: "malformed JSON"))
                }
            }
        }.fetchAll(first).map { it.issues }

    companion object {
        const val TASK_PAGES = 10
        const val COMMENT_PAGES = 5
        val COMMENT_WINDOW: Duration = Duration.ofDays(30)
    }
}
