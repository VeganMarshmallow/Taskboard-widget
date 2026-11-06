package io.github.veganmarshmallow.taskboard.core.board

import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.model.Issue

/** Why a task's PR is not the one to merge next (#45). The row shows it next to a muted Merge. */
enum class MergeWait {
    /** Ready too, but another PR was ready first. */
    QUEUED,
    DRAFT,

    /** Its base is another PR's branch. */
    STACKED,
    CHECKS_RUNNING,
    CHECKS_FAILED,

    /** A run GitHub holds for approval, and no passing build of the same commit replaces it (#64). */
    CHECKS_APPROVAL,

    /** No workflow run on the current head yet (just updated), or none passed. */
    NO_CHECKS,

    /** `mergeable` is false. */
    CONFLICT,

    /** The `needs-rebase` label: the update workflow hit a conflict, and Claude resolves it on its next run. */
    CONFLICT_CLAUDE_FIXES,

    /** Behind the default branch, or GitHub is still computing `mergeable`: the update workflow is on it. */
    UPDATING,

    /** No open PR says it closes the task (or the task names no repo). */
    NO_PR,

    /** The PR could not be checked: no access, network trouble, or the check ran out of time. */
    UNKNOWN,
}

/**
 * A Merge row's place in line (#45). [isNext]: the PR to merge first, shown in the strong shade.
 * Otherwise [wait] says why not, and [firstTitle] is the merge-next task's title, if there is one
 * ("Merge <title> first" when the muted button is tapped).
 */
data class MergeLane(val isNext: Boolean, val wait: MergeWait?, val firstTitle: String?) {
    companion object {
        val NEXT = MergeLane(isNext = true, wait = null, firstTitle = null)
    }
}

/** Works out the [MergeLane] of every task offering Merge, by issue number. Must not throw. */
fun interface MergeLaneSource {
    fun lanes(issues: List<Issue>, api: GitHubApi): Map<Int, MergeLane>
}

/** [board] with each Merge row's lane from [lanes] (rows without Merge are left alone). */
fun Board.withMergeLanes(lanes: Map<Int, MergeLane>): Board = copy(
    sections = sections.map { content ->
        content.copy(items = content.items.map { if (it.canMerge) it.copy(mergeLane = lanes[it.number]) else it })
    },
)
