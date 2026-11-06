package io.github.veganmarshmallow.taskboard.core.reply

import io.github.veganmarshmallow.taskboard.core.merge.FoundPr
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.merge.MergeStep
import io.github.veganmarshmallow.taskboard.core.merge.PullRequest

/**
 * The comment "Request changes" posts (task #65): a fixed heading the board rules look for, the
 * owner's text as typed, and a line telling the next run to keep working on the task's open PR.
 * Pure.
 */
object ChangeRequestText {
    /** The heading the next run looks for (claude-workspace CLAUDE.md, section 2). */
    const val PREFIX = "**Changes requested by the owner:**"

    fun comment(text: String, pr: PullRequest?): String = "$PREFIX\n\n$text\n\n${nextRun(pr)}"

    /** Names the PR and its branch when they are known; otherwise still says not to open a new PR. */
    fun nextRun(pr: PullRequest?): String {
        val where = if (pr == null) "this task's existing PR, on its branch"
        else "the existing PR ${pr.ref} (branch ${code(pr.headRef)})"
        return "**Next run:** continue on $where: push the changes there and don't open a new PR. " +
            "Then take it back through review and return the task to ${code("status: in-review")}."
    }

    /**
     * The PR to name, from the Merge button's search (task #30): the one Merge would pick, or the
     * single open PR that is linked or mentions the task without saying `Closes`. Null otherwise
     * (none, several, closed, or the search failed).
     */
    fun prOf(step: MergeStep<FoundPr>): PullRequest? = when (step) {
        is MergeStep.Ok -> step.value.pr
        is MergeStep.Stop -> (step.problem as? MergeProblem.NotLinked)?.pr
    }?.takeIf { it.isOpen }

    /** A Markdown code span that survives backticks inside [text]. */
    private fun code(text: String): String = if ('`' in text) "`` $text ``" else "`$text`"
}
