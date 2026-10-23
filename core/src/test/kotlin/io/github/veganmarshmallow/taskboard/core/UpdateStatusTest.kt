package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.update.AvailableUpdate
import io.github.veganmarshmallow.taskboard.core.update.ReleasePage
import io.github.veganmarshmallow.taskboard.core.update.UpdateError
import io.github.veganmarshmallow.taskboard.core.update.UpdateMessages
import io.github.veganmarshmallow.taskboard.core.update.UpdateResult
import io.github.veganmarshmallow.taskboard.core.update.UpdateSchedule
import io.github.veganmarshmallow.taskboard.core.update.UpdateStatus
import io.github.veganmarshmallow.taskboard.core.update.UpdateStatusCodec
import io.github.veganmarshmallow.taskboard.core.update.VersionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class UpdateStatusTest {
    private val update = AvailableUpdate(
        VersionInfo(42, "0.1.42", "abcdef1"),
        "https://github.com/VeganMarshmallow/Taskboard-widget/releases/tag/untagged-1a2b",
    )
    private val messages = UpdateMessages { "14:05" }

    @Test fun offerOnlyWhenNewer() {
        val status = UpdateStatus(NOW, update)
        assertEquals(update, status.offer(41))
        assertNull(status.offer(42))
        assertNull(status.offer(100))
        assertNull(UpdateStatus.NEVER.offer(0))
    }

    @Test fun failedCheckKeepsTheLastKnownUpdate() {
        val failed = UpdateStatus(NOW, update).withResult(UpdateResult.Err(UpdateError.Network), NOW.plusSeconds(60))
        assertEquals(update, failed.available)
        assertEquals(UpdateError.Network, failed.error)
        assertEquals(NOW.plusSeconds(60), failed.checkedAt)
        val ok = failed.withResult(UpdateResult.Ok(update), NOW.plusSeconds(120))
        assertNull(ok.error)
    }

    @Test fun codecRoundTripsEveryError() {
        val errors = listOf(
            UpdateError.NoToken, UpdateError.Network, UpdateError.NoRepoAccess, UpdateError.BadToken,
            UpdateError.NoRelease, UpdateError.ReleaseChanged,
            UpdateError.RateLimited(null), UpdateError.RateLimited(NOW), UpdateError.Server(503),
            UpdateError.Malformed("x \"y\" ✓"), UpdateError.Unexpected("עברית"), null,
        )
        for (e in errors) {
            val s = UpdateStatus(NOW, update, e)
            assertEquals(s, UpdateStatusCodec.decode(UpdateStatusCodec.encode(s)))
        }
        assertEquals(UpdateStatus.NEVER, UpdateStatusCodec.decode(UpdateStatusCodec.encode(UpdateStatus.NEVER)))
        val minimal = UpdateStatus(NOW, AvailableUpdate(VersionInfo(3, "3")))
        assertEquals(minimal, UpdateStatusCodec.decode(UpdateStatusCodec.encode(minimal)))
        assertEquals(ReleasePage.FALLBACK, minimal.available!!.pageUrl)
    }

    @Test fun cacheWrittenByTheInstallerBuildStillShowsTheUpdate() {
        // What build 68 (in-app installer) cached: asset fields, no "page", and a download error kind.
        val old = """{"v":1,"checkedAt":${NOW.toEpochMilli()},"available":{"code":70,"name":"0.1.70","sha":"abcdef1",""" +
            """"apkSha256":"${"a".repeat(64)}","apkSize":700,"assetId":111,"assetName":"taskboard-widget.apk","assetSize":700,""" +
            """"assetUrl":"${assetUrl(111)}"},"error":{"k":"LowStorage","need":10,"free":1}}"""
        val decoded = UpdateStatusCodec.decode(old)
        assertEquals(UpdateStatus(NOW, AvailableUpdate(VersionInfo(70, "0.1.70", "abcdef1"), ReleasePage.FALLBACK), null), decoded)
        assertEquals(70L, decoded.offer(68)!!.version.versionCode)
        listOf("UpToDate", "Corrupt", "Untrusted", "InstallNotAllowed").forEach { kind ->
            assertNull(UpdateStatusCodec.decode("""{"v":1,"checkedAt":1,"error":{"k":"$kind","d":"x"}}""").error)
        }
    }

    @Test fun codecNeverThrowsOnGarbage() {
        listOf(null, "", " ", "{", "[]", "42", """{"v":2}""", """{"v":1,"available":{"code":"x"}}""",
            """{"v":1,"checkedAt":"yesterday"}""", "\u0000￿").forEach {
            val decoded = UpdateStatusCodec.decode(it)
            assertNull(decoded.available)
        }
        // unknown error kinds are dropped, not fatal
        assertEquals(NOW, UpdateStatusCodec.decode("""{"v":1,"checkedAt":${NOW.toEpochMilli()},"error":{"k":"Nope"}}""").checkedAt)
    }

    @Test fun codecReplacesATamperedReleasePageWithTheFallback() {
        val tampered = UpdateStatusCodec.encode(UpdateStatus(NOW, update)).replace("https://github.com", "https://evil.example")
        val decoded = UpdateStatusCodec.decode(tampered).available!!
        assertEquals(update.version, decoded.version)
        assertEquals(ReleasePage.FALLBACK, decoded.pageUrl)
    }

    private fun checked(at: java.time.Instant, error: UpdateError? = null) = UpdateStatus(at, update, error)

    @Test fun scheduleIsOncePerDayAfterASuccessfulCheck() {
        val schedule = UpdateSchedule()
        assertTrue(schedule.isDue(UpdateStatus.NEVER, NOW))
        assertFalse(schedule.isDue(checked(NOW), NOW))
        assertFalse(schedule.isDue(checked(NOW.minus(Duration.ofHours(23))), NOW))
        assertTrue(schedule.isDue(checked(NOW.minus(Duration.ofDays(1))), NOW))
        assertTrue(schedule.isDue(checked(NOW.minus(Duration.ofDays(400))), NOW))
        assertTrue(schedule.isDue(checked(NOW.plus(Duration.ofHours(5))), NOW)) // clock moved back
        assertTrue(UpdateSchedule(Duration.ZERO).isDue(checked(NOW), NOW))
    }

    @Test fun aCheckThatFailedOfflineIsRetriedWithinAnHour() {
        val schedule = UpdateSchedule(Duration.ofDays(1), Duration.ofHours(1))
        assertFalse(schedule.isDue(checked(NOW.minus(Duration.ofMinutes(59)), UpdateError.Network), NOW))
        assertTrue(schedule.isDue(checked(NOW.minus(Duration.ofHours(1)), UpdateError.Network), NOW))
        assertTrue(schedule.isDue(checked(NOW.minus(Duration.ofHours(2)), UpdateError.Server(502)), NOW))
        // The retry wait never exceeds the normal interval.
        assertTrue(UpdateSchedule(Duration.ofMinutes(1), Duration.ofHours(1)).isDue(checked(NOW.minus(Duration.ofMinutes(1)), UpdateError.Network), NOW))
    }

    @Test fun aRateLimitWaitsForItsResetTime() {
        val schedule = UpdateSchedule(Duration.ofDays(1), Duration.ofHours(1))
        val limited = UpdateError.RateLimited(NOW.plus(Duration.ofMinutes(30)))
        assertFalse(schedule.isDue(checked(NOW.minus(Duration.ofHours(2)), limited), NOW))
        assertTrue(schedule.isDue(checked(NOW.minus(Duration.ofHours(2)), limited), NOW.plus(Duration.ofMinutes(30))))
        assertTrue(schedule.isDue(checked(NOW.minus(Duration.ofHours(2)), UpdateError.RateLimited(null)), NOW))
    }

    @Test fun missingAccessMessageSaysExactlyWhatToDo() {
        val text = messages.message(UpdateError.NoRepoAccess)
        // Each step names the exact place in GitHub's fine-grained token editor.
        listOf(
            "Fine-grained tokens", "Repository access → Only select repositories", "VeganMarshmallow/Taskboard-widget",
            "Permissions → Repository permissions", "Contents to Read-only", "token itself doesn't change",
        ).forEach { assertTrue(it, text.contains(it)) }
    }

    @Test fun everyErrorHasAMessage() {
        listOf(
            UpdateError.NoToken, UpdateError.Network, UpdateError.NoRepoAccess, UpdateError.BadToken, UpdateError.NoRelease,
            UpdateError.ReleaseChanged, UpdateError.RateLimited(null), UpdateError.RateLimited(NOW),
            UpdateError.Server(500), UpdateError.Malformed("m"), UpdateError.Unexpected("u"),
        ).forEach { assertTrue(it.toString(), messages.message(it).isNotBlank()) }
        assertTrue(messages.message(UpdateError.RateLimited(NOW)).contains("14:05"))
        assertFalse(messages.message(UpdateError.ReleaseChanged).contains("download"))
    }
}
