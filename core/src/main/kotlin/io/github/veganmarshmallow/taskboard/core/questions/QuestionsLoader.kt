package io.github.veganmarshmallow.taskboard.core.questions

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.preview.ClaudeComments
import io.github.veganmarshmallow.taskboard.core.preview.IssueComment
import io.github.veganmarshmallow.taskboard.core.preview.IssueDetails
import io.github.veganmarshmallow.taskboard.core.preview.PreviewApi
import io.github.veganmarshmallow.taskboard.core.work.guarded

/**
 * One blocking load of the Questions screen (task #82): the tasks, the board repo's recent comments
 * in one listing, then, for up to [maxBackfill] tasks that wait on a question whose comment is older
 * than that listing, the task's own comments. A failed backfill only loses that task's text. Never throws.
 */
class QuestionsLoader(private val api: GitHubApi?, private val maxBackfill: Int = MAX_BACKFILL) {
    fun load(): Outcome<Questions> {
        val github = api ?: return Outcome.Failure(BoardError.NoToken)
        return guarded {
            val questions = QuestionsApi(github)
            questions.fetchTasks().flatMap { tasks ->
                questions.fetchRecentComments().map { recent ->
                    val byIssue = recent.groupBy({ it.issue }, { it.comment })
                    QuestionFinder.find(tasks, byIssue + backfill(github, tasks, byIssue))
                }
            }
        }
    }

    private fun backfill(github: GitHubApi, tasks: List<IssueDetails>, byIssue: Map<Int, List<IssueComment>>): Map<Int, List<IssueComment>> {
        val preview = PreviewApi(github)
        return tasks.filter { it.issue.isOpen && lacksItsQuestion(it, byIssue[it.issue.number].orEmpty()) }
            .take(maxBackfill)
            .mapNotNull { task ->
                (guarded { preview.fetchRecentComments(task.issue.number) } as? Outcome.Success)?.let { task.issue.number to it.value }
            }
            .toMap()
    }

    /** The task waits on a question, but the comments read so far don't hold it. */
    private fun lacksItsQuestion(task: IssueDetails, comments: List<IssueComment>): Boolean {
        val labels = task.issue.labels
        val needsClaudeComment = Labels.NEEDS_OWNER in labels && comments.none { ClaudeComments.isFromClaude(it.body) }
        val needsOwnerQuestion = Labels.QUESTION_FOR_CLAUDE in labels && !QuestionText.isQuestion(task.body) &&
            comments.none { QuestionText.isQuestion(it.body) }
        return needsClaudeComment || needsOwnerQuestion
    }

    companion object {
        const val MAX_BACKFILL = 10
    }
}
