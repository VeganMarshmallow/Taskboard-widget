package io.github.veganmarshmallow.taskboard.core.reply

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.merge.PullRequest
import io.github.veganmarshmallow.taskboard.core.merge.TaskPrLookup
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.work.crashError

/**
 * Answers Claude from the task preview (tasks #44, #65), on the board repo [api] is configured with.
 * Blocking; never throws. Steps, in order:
 * 1. for "Request changes" only, find the task's open PR the way Merge does ([findPr]), so the
 *    comment can name it; a failed search still posts, just without the PR's number;
 * 2. post the comment (never retried: a lost answer may still mean it was saved);
 * 3. with a [StatusMove], add `status: todo` first, then remove the move's labels. Adding first
 *    means a half-done change leaves the task visible to the next run rather than stuck with no
 *    status at all.
 * A failure in step 3 is reported as the move's step, so the form retries only the labels
 * ([ReplyAction.Move]) and the comment is never posted twice.
 */
class ReplyService(
    private val api: GitHubApi,
    private val findPr: (Int) -> PullRequest? = { issue ->
        ChangeRequestText.prOf(TaskPrLookup(api, api.requests.repo).find(issue))
    },
) {
    fun send(issue: Int, action: ReplyAction): ReplyResult = try {
        when (action) {
            is ReplyAction.Post -> post(issue, action)
            is ReplyAction.Move -> move(issue, action.move)
        }
    } catch (e: RuntimeException) {
        ReplyResult.Failed(action.step, crashError(e))
    }

    private fun post(issue: Int, action: ReplyAction.Post): ReplyResult {
        ReplyText.problem(action.text)?.let { return ReplyResult.Failed(ReplyStep.COMMENT, BoardError.Unexpected(it)) }
        val body = commentBody(issue, action)
        ReplyText.problem(body)?.let { return ReplyResult.Failed(ReplyStep.COMMENT, BoardError.Unexpected(it)) }
        val now = api.clock.instant()
        return when (val posted = api.send(api.requests.addComment(issue, body)) { ReplyErrors.forComment(it, now, issue) }) {
            is Outcome.Failure -> ReplyResult.Failed(ReplyStep.COMMENT, posted.error)
            is Outcome.Success -> action.move?.let { move(issue, it) } ?: ReplyResult.Sent(unblocked = false)
        }
    }

    private fun commentBody(issue: Int, action: ReplyAction.Post): String {
        val text = ReplyText.clean(action.text)
        return if (action.move == StatusMove.REQUEST_CHANGES) ChangeRequestText.comment(text, prOrNull(issue)) else text
    }

    /** The PR to name, or null: a bug in the search must not stop the owner's request from posting. */
    private fun prOrNull(issue: Int): PullRequest? = try {
        findPr(issue)
    } catch (e: RuntimeException) {
        null
    }

    private fun move(issue: Int, move: StatusMove): ReplyResult {
        val now = api.clock.instant()
        val added = api.send(api.requests.addLabels(issue, listOf(Labels.TODO))) { ReplyErrors.forAddLabels(it, now, issue) }
        val result = move.removes.fold(added) { done, label ->
            done.flatMap { api.send(api.requests.removeLabel(issue, label)) { ReplyErrors.forRemoveLabel(it, now, issue) } }
        }
        return when (result) {
            is Outcome.Failure -> ReplyResult.Failed(move.step, result.error)
            is Outcome.Success -> ReplyResult.Sent(
                unblocked = move == StatusMove.UNBLOCK,
                changesRequested = move == StatusMove.REQUEST_CHANGES,
            )
        }
    }
}
