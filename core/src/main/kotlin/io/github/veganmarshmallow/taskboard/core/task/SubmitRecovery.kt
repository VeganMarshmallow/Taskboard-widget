package io.github.veganmarshmallow.taskboard.core.task

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubRequests
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import java.time.Duration
import java.time.Instant

/**
 * Decides what happened to a submit whose process died ([SubmitMarker] left behind): GitHub has
 * an issue with exactly that title created around the marker's time ([Result.Created]), it has
 * none ([Result.NotCreated], so retrying is safe), or we can't tell ([Result.CheckFailed],
 * [Result.TooOld]). Pure apart from the one lookup it is given.
 */
object SubmitRecovery {
    /** How far before the marker an issue may be dated: the device's clock may run ahead of GitHub's. */
    val LOOK_BEFORE: Duration = Duration.ofMinutes(5)

    /** How far after: covers GitHub's clock running behind plus a slow request (timeouts are far shorter). */
    val LOOK_AFTER: Duration = Duration.ofMinutes(10)

    /** Older markers are not looked up: one page of recently updated issues may no longer reach back that far. */
    val MAX_AGE: Duration = Duration.ofDays(1)

    sealed class Result {
        abstract val title: String

        data class Created(override val title: String, val number: Int) : Result()
        data class NotCreated(override val title: String) : Result()
        data class CheckFailed(override val title: String, val error: BoardError) : Result()
        data class TooOld(override val title: String) : Result()
    }

    /**
     * The `since` of the lookup. GitHub filters on updated_at, which is never before created_at, so
     * every issue created in the window is included; older issues edited since come too, but sort
     * after it (`sort=created`, newest first) and fail the time check.
     */
    fun lookupSince(marker: SubmitMarker): Instant = marker.startedAt.minus(LOOK_BEFORE)

    fun isTooOld(marker: SubmitMarker, now: Instant): Boolean =
        Duration.between(marker.startedAt, now) > MAX_AGE

    /**
     * The issue the marker's submit created: same title (ignoring surrounding spaces), not a pull
     * request, created within the window. If several match, the first one created (lowest number).
     */
    fun match(marker: SubmitMarker, issues: List<RecentIssue>): Int? {
        val from = marker.startedAt.minus(LOOK_BEFORE)
        val to = marker.startedAt.plus(LOOK_AFTER)
        val title = marker.title.trim()
        return issues.filter { issue ->
            val created = issue.createdAt
            !issue.isPullRequest && issue.title.trim() == title && created != null && created >= from && created <= to
        }.minOfOrNull { it.number }
    }

    /**
     * [pageSize] is the lookup's page size: a full page whose oldest entry is still inside the window
     * may have cut off the issue (over a page of issues created since), so no match there is not a
     * "not created" but a failed check.
     */
    fun decide(marker: SubmitMarker, lookup: Outcome<List<RecentIssue>>, pageSize: Int = GitHubRequests.PER_PAGE): Result =
        when (lookup) {
            is Outcome.Failure -> Result.CheckFailed(marker.title, lookup.error)
            is Outcome.Success -> match(marker, lookup.value)?.let { Result.Created(marker.title, it) }
                ?: if (mayBeCutOff(marker, lookup.value, pageSize)) Result.CheckFailed(marker.title, TOO_MANY) else Result.NotCreated(marker.title)
        }

    private fun mayBeCutOff(marker: SubmitMarker, issues: List<RecentIssue>, pageSize: Int): Boolean {
        if (issues.size < pageSize) return false
        val oldest = issues.last().createdAt ?: return true
        return oldest >= marker.startedAt.minus(LOOK_BEFORE)
    }

    private val TOO_MANY = BoardError.Unexpected("too many new issues since then to tell")

    /** Runs the whole check: [lookup] (given [lookupSince]) is called at most once, and not at all for a too-old marker. */
    fun check(marker: SubmitMarker, now: Instant, lookup: (since: Instant) -> Outcome<List<RecentIssue>>): Result =
        if (isTooOld(marker, now)) Result.TooOld(marker.title) else decide(marker, lookup(lookupSince(marker)))
}
