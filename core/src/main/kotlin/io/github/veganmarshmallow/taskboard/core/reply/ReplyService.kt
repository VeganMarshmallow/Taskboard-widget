package io.github.veganmarshmallow.taskboard.core.reply

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.model.Labels

/**
 * Answers Claude from the task preview (task #44), on the board repo [api] is configured with.
 * Blocking; never throws. Steps, in order:
 * 1. post the comment (never retried: a lost answer may still mean it was saved);
 * 2. when unblocking, add `status: todo` first, then remove `needs-owner` and `status: blocked`.
 *    Adding first means a half-done change leaves the task visible to the next run rather than
 *    stuck with no status at all.
 * A failure in step 2 is reported as [ReplyStep.UNBLOCK], so the form retries only the labels
 * ([ReplyAction.Unblock]) and the comment is never posted twice.
 */
class ReplyService(private val api: GitHubApi) {
    fun send(issue: Int, action: ReplyAction): ReplyResult = try {
        when (action) {
            is ReplyAction.Post -> post(issue, action)
            ReplyAction.Unblock -> unblock(issue)
        }
    } catch (e: RuntimeException) {
        val step = if (action is ReplyAction.Unblock) ReplyStep.UNBLOCK else ReplyStep.COMMENT
        ReplyResult.Failed(step, BoardError.Unexpected("internal error: ${e.javaClass.simpleName}"))
    }

    private fun post(issue: Int, action: ReplyAction.Post): ReplyResult {
        ReplyText.problem(action.text)?.let { return ReplyResult.Failed(ReplyStep.COMMENT, BoardError.Unexpected(it)) }
        val now = api.clock.instant()
        return when (val posted = api.send(api.requests.addComment(issue, ReplyText.clean(action.text))) { ReplyErrors.forComment(it, now, issue) }) {
            is Outcome.Failure -> ReplyResult.Failed(ReplyStep.COMMENT, posted.error)
            is Outcome.Success -> if (action.unblock) unblock(issue) else ReplyResult.Sent(unblocked = false)
        }
    }

    private fun unblock(issue: Int): ReplyResult {
        val now = api.clock.instant()
        val result = api.send(api.requests.addLabels(issue, listOf(Labels.TODO))) { ReplyErrors.forAddLabels(it, now, issue) }
            .flatMap { api.send(api.requests.removeLabel(issue, Labels.NEEDS_OWNER)) { ReplyErrors.forRemoveLabel(it, now, issue) } }
            .flatMap { api.send(api.requests.removeLabel(issue, Labels.BLOCKED)) { ReplyErrors.forRemoveLabel(it, now, issue) } }
        return when (result) {
            is Outcome.Failure -> ReplyResult.Failed(ReplyStep.UNBLOCK, result.error)
            is Outcome.Success -> ReplyResult.Sent(unblocked = true)
        }
    }
}
