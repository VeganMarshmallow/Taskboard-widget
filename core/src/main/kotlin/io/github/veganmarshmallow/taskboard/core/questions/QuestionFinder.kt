package io.github.veganmarshmallow.taskboard.core.questions

import io.github.veganmarshmallow.taskboard.core.board.TitleCleaner
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.preview.ClaudeComments
import io.github.veganmarshmallow.taskboard.core.preview.ClaudeMessage
import io.github.veganmarshmallow.taskboard.core.preview.IssueComment
import io.github.veganmarshmallow.taskboard.core.preview.IssueDetails

/**
 * Finds the questions in the tasks and their comments (task #82). Pure. Comments are put in time
 * order (missing times keep list order); bodies beyond [MAX_BODY] are cut before parsing.
 *
 * Claude's questions, on open tasks other than the board summary (#1):
 * - the task carries `needs-owner`, or Claude's latest comment (not counting its `**Answer:**`
 *   replies) asks something ([ClaudeAsks]);
 * - and the owner has not commented since that Claude comment (Claude's `**Answer:**` replies, with
 *   or without a footer, don't count). Once they have, the question waits
 *   on Claude again, even if `needs-owner` is still there (the next run removes it).
 *
 * The owner's questions: every `**Question for Claude:**` comment, and the body of a general
 * question's issue. The first `**Answer:**` comment after a question answers it (and every other
 * question still open before it). Unanswered ones come first, newest first, then up to
 * [MAX_ANSWERED] answered ones, most recently asked first.
 */
object QuestionFinder {
    const val MAX_BODY = 64 * 1024
    const val MAX_ANSWERED = 20
    private const val SUMMARY_ISSUE = 1

    fun find(tasks: List<IssueDetails>, comments: Map<Int, List<IssueComment>>): Questions {
        val shown = tasks.filter { !it.issue.isPullRequest && it.issue.number != SUMMARY_ISSUE }.distinctBy { it.issue.number }
        val fromClaude = shown.filter { it.issue.isOpen }
            .mapNotNull { claudeQuestion(it, ordered(comments[it.issue.number])) }
            .sortedWith(compareByDescending<ClaudeQuestion> { it.needsOwner }.thenBy { it.issue })
        val asked = shown.flatMap { ownerQuestions(it, ordered(comments[it.issue.number])) }
        val open = asked.filter { !it.answered }.sortedWith(newestFirst)
        val answered = asked.filter { it.answered }.sortedWith(newestFirst).take(MAX_ANSWERED)
        return Questions(fromClaude, open + answered)
    }

    private val newestFirst = compareByDescending<OwnerQuestion, java.time.Instant?>(nullsFirst()) { it.askedAt }.thenByDescending { it.issue }

    private fun ordered(list: List<IssueComment>?): List<IssueComment> = list.orEmpty()
        .map { it.copy(body = it.body.take(MAX_BODY)) }
        .withIndex()
        .sortedWith(compareBy<IndexedValue<IssueComment>, java.time.Instant?>(nullsFirst()) { it.value.createdAt }.thenBy { it.index })
        .map { it.value }

    private fun claudeQuestion(task: IssueDetails, comments: List<IssueComment>): ClaudeQuestion? {
        val needsOwner = Labels.NEEDS_OWNER in task.issue.labels
        val last = comments.indexOfLast { ClaudeComments.isFromClaude(it.body) && !QuestionText.isAnswer(it.body) }
        val message = if (last < 0) null else ClaudeMessage.of(ClaudeComments.withoutPrefix(comments[last].body))
        if (!needsOwner && !ClaudeAsks.asks(message)) return null
        val repliedSince = last >= 0 && comments.drop(last + 1).any { !ClaudeComments.isFromClaude(it.body) && !QuestionText.isAnswer(it.body) }
        if (repliedSince) return null
        return ClaudeQuestion(task.issue.number, title(task), message, comments.getOrNull(last)?.createdAt, needsOwner)
    }

    private fun ownerQuestions(task: IssueDetails, comments: List<IssueComment>): List<OwnerQuestion> {
        val title = title(task)
        val out = ArrayList<OwnerQuestion>()
        val waiting = ArrayList<Int>() // indexes in out still without an answer
        fun ask(text: String, at: java.time.Instant?, general: Boolean) {
            val question = QuestionText.questionText(text)
            if (question.isEmpty()) return
            waiting += out.size
            out += OwnerQuestion(task.issue.number, title, question, at, answer = null, general = general)
        }
        if (QuestionText.isQuestion(task.body)) ask(task.body.take(MAX_BODY), null, general = true)
        comments.forEach { comment ->
            when {
                QuestionText.isAnswer(comment.body) -> {
                    val answer = ClaudeMessage.of(QuestionText.answerText(comment.body)) ?: return@forEach
                    waiting.forEach { out[it] = out[it].copy(answer = answer) }
                    waiting.clear()
                }
                !ClaudeComments.isFromClaude(comment.body) && QuestionText.isQuestion(comment.body) ->
                    ask(comment.body, comment.createdAt, general = false)
            }
        }
        return out
    }

    private fun title(task: IssueDetails) = TitleCleaner.clean(task.issue.title, TITLE_MAX)

    private const val TITLE_MAX = 300
}
