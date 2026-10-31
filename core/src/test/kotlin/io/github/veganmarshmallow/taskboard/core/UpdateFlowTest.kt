package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.update.AvailableUpdate
import io.github.veganmarshmallow.taskboard.core.update.UpdateError
import io.github.veganmarshmallow.taskboard.core.update.UpdateFlow
import io.github.veganmarshmallow.taskboard.core.update.UpdateFlow.Command
import io.github.veganmarshmallow.taskboard.core.update.UpdateFlow.Input
import io.github.veganmarshmallow.taskboard.core.update.UpdateFlow.Step
import io.github.veganmarshmallow.taskboard.core.update.UpdatePresenter.Activity
import io.github.veganmarshmallow.taskboard.core.update.UpdateResult
import io.github.veganmarshmallow.taskboard.core.update.UpdateSchedule
import io.github.veganmarshmallow.taskboard.core.update.UpdateStatus
import io.github.veganmarshmallow.taskboard.core.update.VersionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class UpdateFlowTest {
    private val flow = UpdateFlow(
        onOpen = UpdateSchedule(Duration.ofMinutes(1)),
        background = UpdateSchedule(Duration.ofDays(1), Duration.ofHours(1)),
    )
    private val failed = Activity.Failed(UpdateError.Network)
    private val v41 = AvailableUpdate(VersionInfo(41, "0.1.41"), RELEASE_PAGE)
    private val v42 = AvailableUpdate(VersionInfo(42, "0.1.42"), RELEASE_PAGE)
    private val justChecked = UpdateStatus(NOW.minusSeconds(30), v41)
    private val checkedLongAgo = UpdateStatus(NOW.minus(Duration.ofHours(2)), v41)
    private val installed = 41L

    private fun opened(hasToken: Boolean = true, cached: UpdateStatus = UpdateStatus.NEVER) = Input.Opened(hasToken, cached, NOW)

    // --- Opened ---

    @Test fun openingIdleChecksWhenNeverChecked() =
        assertEquals(Step(Activity.Checking, Command.RunCheck), flow.next(Activity.Idle, opened()))

    @Test fun openingIdleChecksWhenTheLastCheckIsOverAMinuteOld() =
        assertEquals(Step(Activity.Checking, Command.RunCheck), flow.next(Activity.Idle, opened(cached = checkedLongAgo)))

    @Test fun openingRightAfterACheckDoesNotCheckAgain() =
        assertEquals(Step(Activity.Idle), flow.next(Activity.Idle, opened(cached = justChecked)))

    @Test fun openingExactlyAMinuteLaterChecks() {
        val cached = UpdateStatus(NOW.minus(Duration.ofMinutes(1)), v41)
        assertEquals(Step(Activity.Checking, Command.RunCheck), flow.next(Activity.Idle, opened(cached = cached)))
    }

    @Test fun openingWithoutATokenDoesNotCheck() =
        assertEquals(Step(Activity.Idle), flow.next(Activity.Idle, opened(hasToken = false)))

    @Test fun openingClearsAShownFailureAndChecksWhenDue() =
        assertEquals(Step(Activity.Checking, Command.RunCheck), flow.next(failed, opened()))

    @Test fun openingClearsAShownFailureWithoutCheckingWhenNotDue() =
        assertEquals(Step(Activity.Idle), flow.next(failed, opened(cached = justChecked)))

    @Test fun openingClearsANoTokenFailureEvenWithoutAToken() =
        assertEquals(Step(Activity.Idle), flow.next(Activity.Failed(UpdateError.NoToken), opened(hasToken = false)))

    @Test fun openingWhileCheckingKeepsTheCheckAndStartsNoOther() =
        assertEquals(Step(Activity.Checking), flow.next(Activity.Checking, opened()))

    @Test fun openingWithACheckFromTheFutureChecks() {
        val cached = UpdateStatus(NOW.plus(Duration.ofHours(1)), v41) // the clock moved back
        assertEquals(Step(Activity.Checking, Command.RunCheck), flow.next(Activity.Idle, opened(cached = cached)))
    }

    // --- CheckRequested ---

    @Test fun checkFromIdleStartsACheck() =
        assertEquals(Step(Activity.Checking, Command.RunCheck), flow.next(Activity.Idle, Input.CheckRequested))

    @Test fun checkFromFailedStartsACheck() =
        assertEquals(Step(Activity.Checking, Command.RunCheck), flow.next(failed, Input.CheckRequested))

    @Test fun checkWhileCheckingIsIgnored() =
        assertEquals(Step(Activity.Checking), flow.next(Activity.Checking, Input.CheckRequested))

    @Test fun checkIgnoresTheOpenSchedule() {
        // A tap right after a check still checks: only opening the app is throttled.
        assertEquals(Command.RunCheck, flow.next(Activity.Idle, Input.CheckRequested).command)
    }

    // --- CheckFinished ---

    @Test fun successReturnsToIdle() =
        assertEquals(Step(Activity.Idle), flow.next(Activity.Checking, Input.CheckFinished(null)))

    @Test fun noTokenIsShownAsTheSectionsFailure() =
        assertEquals(Step(Activity.Failed(UpdateError.NoToken)), flow.next(Activity.Checking, Input.CheckFinished(UpdateError.NoToken)))

    @Test fun otherErrorsReturnToIdleBecauseTheCachedStatusShowsThem() {
        val errors = listOf(
            UpdateError.Network, UpdateError.BadToken, UpdateError.NoRepoAccess, UpdateError.NoRelease,
            UpdateError.ReleaseChanged, UpdateError.RateLimited(null), UpdateError.Server(502),
            UpdateError.Malformed("x"), UpdateError.Unexpected("y"),
        )
        errors.forEach { assertEquals(it.toString(), Step(Activity.Idle), flow.next(Activity.Checking, Input.CheckFinished(it))) }
    }

    @Test fun aFinishedCheckNeverStartsAnother() =
        assertNull(flow.next(Activity.Checking, Input.CheckFinished(UpdateError.Network)).command)

    // --- ReleasePageFailed ---

    @Test fun releasePageFailureIsShownFromIdle() {
        val error = UpdateError.Unexpected("no browser to open the release page")
        assertEquals(Step(Activity.Failed(error)), flow.next(Activity.Idle, Input.ReleasePageFailed(error)))
    }

    @Test fun releasePageFailureReplacesAnEarlierFailure() {
        val error = UpdateError.Unexpected("no browser")
        assertEquals(Step(Activity.Failed(error)), flow.next(failed, Input.ReleasePageFailed(error)))
    }

    @Test fun releasePageFailureNeverStartsACheck() =
        assertNull(flow.next(Activity.Checking, Input.ReleasePageFailed(UpdateError.Network)).command)

    // --- Background check ---

    @Test fun backgroundCheckIsDueWhenNeverChecked() = assertTrue(flow.backgroundCheckDue(UpdateStatus.NEVER, NOW))

    @Test fun backgroundCheckWaitsADayAfterASuccess() {
        assertFalse(flow.backgroundCheckDue(UpdateStatus(NOW.minus(Duration.ofHours(23)), v41), NOW))
        assertTrue(flow.backgroundCheckDue(UpdateStatus(NOW.minus(Duration.ofDays(1)), v41), NOW))
    }

    @Test fun backgroundCheckRetriesAnHourAfterAFailure() {
        assertFalse(flow.backgroundCheckDue(UpdateStatus(NOW.minusSeconds(59 * 60), v41, UpdateError.Network), NOW))
        assertTrue(flow.backgroundCheckDue(UpdateStatus(NOW.minus(Duration.ofHours(1)), v41, UpdateError.Network), NOW))
    }

    @Test fun backgroundCheckIgnoresTheOneMinuteOpenSchedule() =
        assertFalse(flow.backgroundCheckDue(UpdateStatus(NOW.minus(Duration.ofMinutes(5)), v41), NOW))

    // --- record ---

    @Test fun noTokenCachesNothing() =
        assertEquals(UpdateFlow.Recorded(null, false, UpdateError.NoToken), flow.record(checkedLongAgo, null, NOW, installed))

    @Test fun successCachesTheReleaseAndClearsTheError() {
        val before = UpdateStatus(NOW.minus(Duration.ofDays(2)), v41, UpdateError.Network)
        val r = flow.record(before, UpdateResult.Ok(v41), NOW, installed)
        assertEquals(UpdateStatus(NOW, v41, null), r.status)
        assertNull(r.error)
        assertFalse(r.badgeChanged)
    }

    @Test fun aNewerReleaseTurnsTheBadgeOn() {
        val r = flow.record(checkedLongAgo, UpdateResult.Ok(v42), NOW, installed)
        assertEquals(UpdateStatus(NOW, v42), r.status)
        assertTrue(r.badgeChanged)
    }

    @Test fun aPulledBackReleaseTurnsTheBadgeOff() {
        val before = UpdateStatus(NOW.minus(Duration.ofDays(1)), v42) // offered while 41 is installed
        val r = flow.record(before, UpdateResult.Ok(v41), NOW, installed)
        assertTrue(r.badgeChanged)
    }

    @Test fun anOfferStillShownIsNotABadgeChange() {
        val before = UpdateStatus(NOW.minus(Duration.ofDays(1)), v42)
        assertFalse(flow.record(before, UpdateResult.Ok(v42), NOW, installed).badgeChanged)
    }

    @Test fun aFailedCheckKeepsTheCachedReleaseAndReportsTheError() {
        val before = UpdateStatus(NOW.minus(Duration.ofDays(1)), v42)
        val r = flow.record(before, UpdateResult.Err(UpdateError.Network), NOW, installed)
        assertEquals(UpdateStatus(NOW, v42, UpdateError.Network), r.status)
        assertEquals(UpdateError.Network, r.error)
        assertFalse(r.badgeChanged) // the dot stays on through an offline check
    }

    @Test fun aFailedFirstCheckCachesTheErrorWithNoBadge() {
        val r = flow.record(UpdateStatus.NEVER, UpdateResult.Err(UpdateError.BadToken), NOW, installed)
        assertEquals(UpdateStatus(NOW, null, UpdateError.BadToken), r.status)
        assertFalse(r.badgeChanged)
    }

    @Test fun anOlderReleaseThanInstalledIsNoBadge() {
        val r = flow.record(UpdateStatus.NEVER, UpdateResult.Ok(v41), NOW, installedVersionCode = 42)
        assertFalse(r.badgeChanged)
    }
}
