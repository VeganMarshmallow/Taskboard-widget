package io.github.veganmarshmallow.taskboard.core.mergeall

import io.github.veganmarshmallow.taskboard.core.merge.PullRef
import java.time.Instant

/** Where a Merge all run is with its current PR (#57). Each phase can be re-run after a process death. */
enum class MergeAllPhase {
    /** Read the PR; retarget it onto the default branch if its base PR was merged in this run. */
    PREPARE,

    /** Bring it up to date with the default branch (`update-branch`), unless it already is. */
    UPDATE,

    /** Wait for the new head and for every Actions workflow on it. */
    WAIT,

    /** Merge, pinned to the checked head. */
    MERGE,
}

/** Why a run stopped at a PR (#57). [detail]: workflow names, or GitHub's own message. */
data class MergeAllStop(val kind: Kind, val detail: String? = null) {
    enum class Kind {
        CONFLICT, CHECKS_FAILED, CHECKS_TIMEOUT, CHECKS_APPROVAL, NO_CHECKS, PR_CHANGED, PR_CLOSED, DRAFT, STACKED,
        NO_ACCESS, NO_ACTIONS_ACCESS, RATE_LIMITED, NO_TOKEN, NETWORK, ERROR,
    }
}

/** How a run ended. */
sealed class MergeAllEnd {
    data object Done : MergeAllEnd()
    data object Cancelled : MergeAllEnd()

    /** Stopped at the plan's PR number [at] (an index into the plan). */
    data class Stopped(val at: Int, val stop: MergeAllStop) : MergeAllEnd()
}

/** A PR this run merged, and its head branch (a PR stacked on that branch is retargeted, #57). */
data class MergedPr(val ref: PullRef, val headRef: String)

/**
 * A Merge all run, saved after every step so a process death resumes it where it was (#57).
 *
 * - [index]: the plan's current PR; [phase]: its step.
 * - [oldHead]: the head before `update-branch` (WAIT waits until the head is no longer it).
 * - [checkedHead]: the head the checks are judged on and the merge is pinned to.
 * - [waitSince]: when the current PR started waiting; the wait is bounded from there.
 * - [offline]: the last poll found no network, so it waits for one rather than for checks (#78).
 * - [end]: null while the run goes on.
 * - [updatedAt]: the last step (poll); a run not polled for long has stalled ([MergeAllHealth]).
 */
data class MergeAllState(
    val runId: String,
    val plan: List<PlannedMerge>,
    val index: Int = 0,
    val phase: MergeAllPhase = MergeAllPhase.PREPARE,
    val merged: List<MergedPr> = emptyList(),
    val oldHead: String? = null,
    val checkedHead: String? = null,
    val waitSince: Instant? = null,
    val offline: Boolean = false,
    val end: MergeAllEnd? = null,
    val updatedAt: Instant,
) {
    init {
        require(plan.isNotEmpty()) { "empty plan" }
        require(index in plan.indices) { "index out of the plan" }
        require((end as? MergeAllEnd.Stopped)?.at?.let { it in plan.indices } ?: true) { "stop out of the plan" }
    }

    val running: Boolean get() = end == null
    val current: PlannedMerge get() = plan[index]

    /** The current PR moves on to [next]. */
    fun at(next: MergeAllPhase, now: Instant) = copy(phase = next, updatedAt = now)

    fun stopped(stop: MergeAllStop, now: Instant) = copy(end = MergeAllEnd.Stopped(index, stop), updatedAt = now)

    fun cancelled(now: Instant) = copy(end = MergeAllEnd.Cancelled, updatedAt = now)

    /** The current PR merged: on to the next one, or done. */
    fun mergedCurrent(headRef: String, now: Instant): MergeAllState {
        val done = merged + MergedPr(current.pr, headRef)
        return if (index == plan.lastIndex) copy(merged = done, end = MergeAllEnd.Done, updatedAt = now)
        else copy(merged = done, index = index + 1, phase = MergeAllPhase.PREPARE, oldHead = null, checkedHead = null, waitSince = null, updatedAt = now)
    }

    companion object {
        fun start(runId: String, plan: List<PlannedMerge>, now: Instant) = MergeAllState(runId, plan, updatedAt = now)
    }
}

/** Where a run is kept (#57). Cancel is kept apart, so a step saving its state never loses a tap on Cancel. */
interface MergeAllStore {
    fun load(): MergeAllState?

    /**
     * Saves [state] as a new run, unless an unfinished run is saved (#60): a compare-and-set, so of
     * two starts at the same moment (two widgets) exactly one wins. False: refused, nothing saved.
     */
    fun start(state: MergeAllState): Boolean
    fun save(state: MergeAllState)
    fun clear()
    fun requestCancel(runId: String)
    fun cancelRequested(runId: String): Boolean
}
