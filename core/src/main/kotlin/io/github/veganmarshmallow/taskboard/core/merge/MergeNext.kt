package io.github.veganmarshmallow.taskboard.core.merge

import io.github.veganmarshmallow.taskboard.core.board.MergeLane
import io.github.veganmarshmallow.taskboard.core.board.MergeWait

/**
 * A task offering Merge, with its PR's number (null when none was found) and [readiness]. [pr]: the
 * PR as the repo's open list showed it (null when none was found), for Merge all's stacks (#57).
 */
data class MergeCandidate(
    val issue: Int,
    val title: String,
    val prNumber: Int?,
    val readiness: Readiness,
    val pr: PullRequest? = null,
)

/**
 * Picks the one PR to merge next (#45): among the ready ones, the one ready first ([Readiness.Ready.since],
 * an unknown time last), then the oldest PR (lowest number), then the lowest task number so the
 * choice is always the same. None ready: no merge-next, and every row keeps its reason.
 */
object MergeNext {
    private val ORDER = compareBy<MergeCandidate, java.time.Instant?>(nullsLast()) { (it.readiness as Readiness.Ready).since }
        .thenBy { it.prNumber ?: Int.MAX_VALUE }
        .thenBy { it.issue }

    fun pick(candidates: List<MergeCandidate>): MergeCandidate? = ranked(candidates).firstOrNull()

    /** The ready candidates, the one to merge next first (Merge all's order, #57). */
    fun ranked(candidates: List<MergeCandidate>): List<MergeCandidate> =
        candidates.filter { it.readiness is Readiness.Ready }.sortedWith(ORDER)

    /**
     * Each candidate's lane, by issue number (a task listed twice keeps its first entry). [inRun]:
     * the issues Merge all's plan takes (#60); null leaves [MergeLane.inRun] to its guess from the wait.
     */
    fun lanes(candidates: List<MergeCandidate>, inRun: Set<Int>? = null): Map<Int, MergeLane> {
        val unique = candidates.distinctBy { it.issue }
        val next = pick(unique)
        return unique.associate { c ->
            val lane = if (c == next) MergeLane.NEXT else MergeLane(isNext = false, wait = waitOf(c.readiness), firstTitle = next?.title)
            c.issue to if (inRun == null) lane else lane.copy(inRun = c.issue in inRun)
        }
    }

    private fun waitOf(readiness: Readiness): MergeWait = when (readiness) {
        is Readiness.Ready -> MergeWait.QUEUED
        is Readiness.Waiting -> readiness.wait
    }
}
