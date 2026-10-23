package io.github.veganmarshmallow.taskboard.core.preview

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.error.ErrorClassifier
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.github.LinkHeader
import io.github.veganmarshmallow.taskboard.core.github.MalformedResponseException
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import io.github.veganmarshmallow.taskboard.core.http.Outcome

/** Loads one issue and its newest comments for the preview, through [GitHubApi]'s request pipeline. */
class PreviewApi(private val api: GitHubApi) {
    /** 404/410/301 mean the issue itself is gone (the board was readable a moment ago). */
    fun fetchIssue(number: Int): Outcome<IssueDetails> =
        api.send(api.requests.issue(number)) { ErrorClassifier.classifyApprove(it, api.clock.instant(), number) }
            .flatMap { parse(it) { body -> PreviewJsonParser.parseIssue(body) } }
            .flatMap { details ->
                // A redirect is never followed, so a different number means GitHub sent something else.
                if (details.issue.number == number) Outcome.Success(details)
                else Outcome.Failure(BoardError.Unexpected("issue number mismatch"))
            }

    /**
     * The first page of comments plus, when there are more pages, the last one: the newest comments
     * are on the last page, and the first page alone is enough for almost every task.
     */
    fun fetchRecentComments(number: Int): Outcome<List<IssueComment>> =
        api.send(api.requests.comments(number)).flatMap { first ->
            parse(first, PreviewJsonParser::parseComments).flatMap { comments ->
                val relations = LinkHeader.relations(first.header("link"))
                val last = relations["last"]?.takeIf { relations.containsKey("next") }
                if (last == null) Outcome.Success(comments) else fetchPage(last).map { comments + it }
            }
        }

    private fun fetchPage(url: String): Outcome<List<IssueComment>> {
        val request = try {
            api.requests.page(url)
        } catch (e: IllegalArgumentException) {
            return Outcome.Failure(BoardError.Unexpected("bad pagination link"))
        }
        return api.send(request).flatMap { parse(it, PreviewJsonParser::parseComments) }
    }

    private fun <T> parse(response: HttpResponse, parser: (String) -> T): Outcome<T> = try {
        Outcome.Success(parser(response.body))
    } catch (e: MalformedResponseException) {
        Outcome.Failure(BoardError.Unexpected(e.message ?: "malformed JSON"))
    }
}
