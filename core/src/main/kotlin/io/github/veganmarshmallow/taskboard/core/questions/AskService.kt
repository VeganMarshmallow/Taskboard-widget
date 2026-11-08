package io.github.veganmarshmallow.taskboard.core.questions

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.reply.ReplyErrors
import io.github.veganmarshmallow.taskboard.core.reply.ReplyResult
import io.github.veganmarshmallow.taskboard.core.reply.ReplyStep
import io.github.veganmarshmallow.taskboard.core.reply.ReplyText
import io.github.veganmarshmallow.taskboard.core.task.NewIssue
import io.github.veganmarshmallow.taskboard.core.work.crashError

/**
 * Asks Claude a question (task #82), on the board repo [api] is configured with. Blocking; never
 * throws. The result is a [ReplyResult], so the ask box is a [io.github.veganmarshmallow.taskboard.core.reply.ReplyForm] like the reply box.
 *
 * - About a task: post `**Question for Claude:**` + the text as a comment, then add the
 *   `question-for-claude` label. The comment is never retried (a lost answer may still mean it was
 *   saved). A failed label still counts as sent: the board rules find the question by its prefix too.
 * - General ([task] null): create an issue with the question as its body, labelled
 *   `question-for-claude` and `status: todo` (never `ready-for-claude`: it is not a task to start).
 */
class AskService(private val api: GitHubApi) {
    fun ask(task: Int?, text: String): ReplyResult = try {
        val body = QuestionText.comment(text)
        val problem = ReplyText.problem(text) ?: ReplyText.problem(body)
        when {
            problem != null -> failed(BoardError.Unexpected(problem))
            task == null -> createIssue(text, body)
            else -> comment(task, body)
        }
    } catch (e: RuntimeException) {
        failed(crashError(e))
    }

    private fun comment(task: Int, body: String): ReplyResult {
        val now = api.clock.instant()
        return when (val posted = api.send(api.requests.addComment(task, body)) { ReplyErrors.forComment(it, now, task) }) {
            is Outcome.Failure -> failed(posted.error)
            is Outcome.Success -> {
                api.send(api.requests.addLabels(task, listOf(Labels.QUESTION_FOR_CLAUDE))) { ReplyErrors.forAddLabels(it, now, task) }
                ReplyResult.Sent(unblocked = false)
            }
        }
    }

    private fun createIssue(text: String, body: String): ReplyResult =
        when (val created = api.createIssue(NewIssue(QuestionText.issueTitle(text), body, LABELS))) {
            is Outcome.Failure -> failed(created.error)
            is Outcome.Success -> ReplyResult.Sent(unblocked = false)
        }

    private fun failed(error: BoardError) = ReplyResult.Failed(ReplyStep.COMMENT, error)

    companion object {
        val LABELS = listOf(Labels.QUESTION_FOR_CLAUDE, Labels.TODO)
    }
}
