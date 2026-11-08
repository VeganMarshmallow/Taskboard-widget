package io.github.veganmarshmallow.taskboard.core.mergeall

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.merge.MergeStep
import io.github.veganmarshmallow.taskboard.core.merge.PullRequest
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllPhase.MERGE
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllPhase.PREPARE
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllPhase.UPDATE
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllPhase.WAIT
import io.github.veganmarshmallow.taskboard.core.pace.WorkPace
import java.time.Duration

/**
 * Merge all's state machine (#57): one step of a run at a time, each starting from what GitHub says
 * now, so any step can run again after a process death. Per PR: PREPARE (retarget a PR whose base
 * PR was merged in this run) → UPDATE (`update-branch`, unless up to date) → WAIT (the new head, then
 * the Merge button's checks on it) → MERGE (pinned to the checked head) → the next PR.
 *
 * - The first problem stops the run; PRs merged so far stay merged.
 * - Cancel is honoured at the start of a step: a step already sent is finished, nothing more merges.
 * - Each step checks the network first (#78): offline, it waits for one without calling GitHub, and
 *   the run says "waiting for network". A run picked up after a stall ([MergeAllHealth]) doesn't
 *   count the stalled time toward [checksTimeout].
 * - Checks running, a head not updated yet, and network trouble are waited out, up to [checksTimeout]
 *   per PR. A failed merge request without a clear answer is retried only after re-reading the PR,
 *   so a merge that went through is counted, never sent twice.
 * - #83, [pace] Faster: a PR behind its default branch after this run already merged one, whose own
 *   checks are green right now, is merged as it is ([mergeAsIs]) instead of updated and re-tested.
 *   Otherwise (Save usage, the first PR, checks not green) it is updated and waited for as before.
 */
class MergeAllMachine(
    private val github: MergeAllGitHub,
    private val poll: Duration = POLL,
    private val checksTimeout: Duration = CHECKS_TIMEOUT,
    private val staleAfter: Duration = MergeAllHealth.staleAfter(poll),
    private val pace: WorkPace = WorkPace.DEFAULT,
) {
    /** The state after a step, and how long to wait before the next one (null: go on at once). */
    data class Step(val state: MergeAllState, val waitFor: Duration? = null)

    /** One step of the [saved] run. [online]: whether the device has a network now (checked before any call). */
    fun step(saved: MergeAllState, cancel: Boolean, online: Boolean = true): Step {
        if (!saved.running) return Step(saved)
        val resumed = MergeAllHealth.resumed(saved, github.now(), staleAfter)
        if (cancel && resumed.phase != MERGE) return Step(resumed.cancelled(github.now()))
        if (!online) return keepWaiting(resumed.copy(offline = true), MergeProblem.Failed(BoardError.Network))
        val state = resumed.copy(offline = false)
        return when (state.phase) {
            PREPARE -> prepare(state)
            UPDATE -> update(state)
            WAIT -> waitForChecks(state)
            MERGE -> merge(state, cancel)
        }
    }

    private fun prepare(state: MergeAllState): Step = read(state) { pr ->
        val started = state.copy(waitSince = github.now())
        when {
            !pr.isStacked -> Step(started.at(UPDATE, github.now()))
            state.merged.none { it.ref.repo == pr.repo && it.headRef == pr.baseRef } -> stop(state, MergeAllStop(MergeAllStop.Kind.STACKED, pr.baseRef))
            else -> when (val moved = github.retarget(pr, pr.defaultBranch!!)) {
                // Retargeting twice is harmless: a lost answer is retried after reading the PR again.
                is MergeStep.Stop -> if (MergeAllStops.transient(moved.problem)) keepWaiting(state, moved.problem) else stop(state, moved.problem)
                is MergeStep.Ok -> Step(started.at(UPDATE, github.now()))
            }
        }
    }

    private fun update(state: MergeAllState): Step = read(state) { pr ->
        when (val behind = github.behindBy(pr)) {
            is MergeStep.Stop -> waitOrStop(state, behind.problem)
            is MergeStep.Ok -> if (behind.value == 0L) {
                Step(state.copy(oldHead = null, checkedHead = pr.headSha).at(WAIT, github.now()))
            } else {
                mergeAsIs(state, pr) ?: when (val sent = github.updateBranch(pr)) {
                    is MergeStep.Ok -> Step(state.copy(oldHead = pr.headSha, checkedHead = null).at(WAIT, github.now()), poll)
                    // The repo's update workflow moved the branch first, or the answer was lost: look again
                    // once it settles (the request is pinned to the head, so it never updates twice).
                    is MergeStep.Stop -> if (sent.problem is MergeProblem.HeadChanged || MergeAllStops.transient(sent.problem)) {
                        keepWaiting(state, sent.problem)
                    } else {
                        stop(state, sent.problem)
                    }
                }
            }
        }
    }

    private fun waitForChecks(state: MergeAllState): Step = read(state) { pr ->
        val head = pr.headSha
        when {
            state.oldHead != null && head.equals(state.oldHead, ignoreCase = true) ->
                keepWaiting(state, MergeProblem.ChecksRunning(pr, listOf("update with ${pr.defaultBranch}")))
            state.checkedHead != null && !head.equals(state.checkedHead, ignoreCase = true) -> stop(state, MergeProblem.HeadChanged(pr))
            else -> checkHead(state.copy(oldHead = null, checkedHead = head), pr)
        }
    }

    private fun checkHead(state: MergeAllState, pr: PullRequest): Step = when (val behind = github.behindBy(pr)) {
        is MergeStep.Stop -> waitOrStop(state, behind.problem)
        // The default branch moved again meanwhile: update once more.
        is MergeStep.Ok -> if (behind.value > 0) {
            mergeAsIs(state, pr) ?: Step(state.copy(checkedHead = null).at(UPDATE, github.now()))
        } else {
            when (val checked = github.checks(pr)) {
                is MergeStep.Ok -> Step(state.at(MERGE, github.now()))
                is MergeStep.Stop -> waitOrStop(state, checked.problem)
            }
        }
    }

    private fun merge(state: MergeAllState, cancel: Boolean): Step {
        val pr = when (val read = github.pull(state.current.pr)) {
            is MergeStep.Stop -> return waitOrStop(state, read.problem)
            is MergeStep.Ok -> read.value
        }
        if (pr.isMerged) return Step(state.mergedCurrent(pr.headRef, github.now())) // sent before a process death
        if (cancel) return Step(state.cancelled(github.now()))
        refusal(pr)?.let { return stop(state, it) }
        val checked = state.checkedHead ?: return Step(state.at(WAIT, github.now()))
        if (!pr.headSha.equals(checked, ignoreCase = true)) return stop(state, MergeProblem.HeadChanged(pr))
        return when (val merged = github.merge(pr)) {
            is MergeStep.Ok -> Step(state.mergedCurrent(pr.headRef, github.now()))
            is MergeStep.Stop -> when {
                merged.problem is MergeProblem.AlreadyMerged -> Step(state.mergedCurrent(pr.headRef, github.now()))
                // No clear answer: the next step re-reads the PR before trying again.
                MergeAllStops.transient(merged.problem) -> keepWaiting(state, merged.problem)
                else -> stop(state, merged.problem)
            }
        }
    }

    /**
     * #83 Faster only: once this run merged a PR, a PR it left behind goes straight to MERGE at its
     * current head if that head's checks are green now. Null otherwise: update it as in Save usage.
     */
    private fun mergeAsIs(state: MergeAllState, pr: PullRequest): Step? {
        if (!pace.mergesBackToBack || state.merged.isEmpty()) return null
        if (github.checks(pr) !is MergeStep.Ok) return null
        return Step(state.copy(oldHead = null, checkedHead = pr.headSha).at(MERGE, github.now()))
    }

    /**
     * Reads the current PR, then [next] unless it was closed or became a draft. A PR merged by
     * someone else meanwhile counts as merged. A network failure is waited out like a running check.
     */
    private fun read(state: MergeAllState, next: (PullRequest) -> Step): Step {
        val pr = when (val read = github.pull(state.current.pr)) {
            is MergeStep.Stop -> return waitOrStop(state, read.problem)
            is MergeStep.Ok -> read.value
        }
        if (pr.isMerged) return Step(state.mergedCurrent(pr.headRef, github.now()))
        refusal(pr)?.let { return stop(state, it) }
        return next(pr)
    }

    private fun refusal(pr: PullRequest): MergeProblem? = when {
        !pr.isOpen -> MergeProblem.Closed(pr)
        pr.isDraft -> MergeProblem.Draft(pr)
        else -> null
    }

    private fun waitOrStop(state: MergeAllState, problem: MergeProblem): Step =
        if (MergeAllStops.stillWaiting(problem) || MergeAllStops.transient(problem)) keepWaiting(state, problem) else stop(state, problem)

    /** Waits [poll] and tries the step again, unless the PR has waited [checksTimeout]: then it stops on [problem]. */
    private fun keepWaiting(state: MergeAllState, problem: MergeProblem): Step {
        val now = github.now()
        val since = state.waitSince ?: now
        if (Duration.between(since, now) >= checksTimeout) return stop(state, problem)
        return Step(state.copy(waitSince = since, updatedAt = now), poll)
    }

    private fun stop(state: MergeAllState, problem: MergeProblem): Step = stop(state, MergeAllStops.of(problem))

    private fun stop(state: MergeAllState, stop: MergeAllStop): Step = Step(state.stopped(stop, github.now()))

    companion object {
        /** Between looks at a PR's checks: a full CI run takes about 20 minutes. */
        val POLL: Duration = Duration.ofSeconds(30)

        /** How long one PR may wait for its update and checks before the run stops. */
        val CHECKS_TIMEOUT: Duration = Duration.ofMinutes(60)
    }
}
