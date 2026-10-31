package io.github.veganmarshmallow.taskboard.core.merge

import io.github.veganmarshmallow.taskboard.core.board.MergeLane
import io.github.veganmarshmallow.taskboard.core.board.MergeWait

/** A task offering Merge, with its PR's number (null when none was found) and [readiness]. */
data class MergeCandidate(val issue: Int, val title: String, val prNumber: Int?, val readiness: Readiness)

/**
 * Picks the one PR to merge next (#45): among the ready ones, the one ready first ([Readiness.Ready.since],
 * an unknown time last), then the oldest PR (lowest number), then the lowest task number so the
 * choice is always the same. None ready: no merge-next, and every row keeps its reason.
 */
object MergeNext {
    private val ORDER = compareBy<MergeCandidate, java.time.Instant?>(nullsLast()) { (it.readiness as Readiness.Ready).since }
        .thenBy { it.prNumber ?: Int.MAX_VALUE }
        .thenBy { it.issue }

    fun pick(candidates: List<MergeCandidate>): MergeCandidate? =
        candidates.filter { it.readiness is Readiness.Ready }.minWithOrNull(ORDER)

    /** Each candidate's lane, by issue number (a task listed twice keeps its first entry). */
    fun lanes(candidates: List<MergeCandidate>): Map<Int, MergeLane> {
        val unique = candidates.distinctBy { it.issue }
        val next = pick(unique)
        return unique.associate { c ->
            c.issue to when {
                c == next -> MergeLane.NEXT
                else -> MergeLane(isNext = false, wait = waitOf(c.readiness), firstTitle = next?.title)
            }
        }
    }

    private fun waitOf(readiness: Readiness): MergeWait = when (readiness) {
        is Readiness.Ready -> MergeWait.QUEUED
        is Readiness.Waiting -> readiness.wait
    }
}
