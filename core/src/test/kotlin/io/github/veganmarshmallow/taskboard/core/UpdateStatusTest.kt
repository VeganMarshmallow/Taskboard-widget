package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.update.AvailableUpdate
import io.github.veganmarshmallow.taskboard.core.update.ReleaseAsset
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
        VersionInfo(42, "0.1.42", "abcdef1", "a".repeat(64), 700),
        ReleaseAsset(APK_ID, "taskboard-widget.apk", 700, assetUrl(APK_ID)),
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
            UpdateError.NoRelease, UpdateError.ReleaseChanged, UpdateError.UpToDate,
            UpdateError.RateLimited(null), UpdateError.RateLimited(NOW), UpdateError.Server(503),
            UpdateError.Malformed("x \"y\" ✓"), UpdateError.Corrupt("SHA-256 mismatch"), UpdateError.LowStorage(10, -1),
            UpdateError.Unexpected("עברית"), null,
        )
        for (e in errors) {
            val s = UpdateStatus(NOW, update, e)
            assertEquals(s, UpdateStatusCodec.decode(UpdateStatusCodec.encode(s)))
        }
        assertEquals(UpdateStatus.NEVER, UpdateStatusCodec.decode(UpdateStatusCodec.encode(UpdateStatus.NEVER)))
        val minimal = UpdateStatus(NOW, update.copy(version = VersionInfo(3, "3")))
        assertEquals(minimal, UpdateStatusCodec.decode(UpdateStatusCodec.encode(minimal)))
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

    @Test fun codecRefusesACachedNonApiAssetUrl() {
        val tampered = UpdateStatusCodec.encode(UpdateStatus(NOW, update)).replace("https://api.github.com", "https://evil.example")
        assertNull(UpdateStatusCodec.decode(tampered).available)
    }

    @Test fun scheduleIsOncePerDay() {
        val schedule = UpdateSchedule()
        assertTrue(schedule.isDue(null, NOW))
        assertFalse(schedule.isDue(NOW, NOW))
        assertFalse(schedule.isDue(NOW.minus(Duration.ofHours(23)), NOW))
        assertTrue(schedule.isDue(NOW.minus(Duration.ofDays(1)), NOW))
        assertTrue(schedule.isDue(NOW.minus(Duration.ofDays(400)), NOW))
        assertTrue(schedule.isDue(NOW.plus(Duration.ofHours(5)), NOW)) // clock moved back
        assertTrue(UpdateSchedule(Duration.ZERO).isDue(NOW, NOW))
    }

    @Test fun missingAccessMessageSaysExactlyWhatToDo() {
        val text = messages.message(UpdateError.NoRepoAccess)
        assertTrue(text.contains("VeganMarshmallow/Taskboard-widget"))
        assertTrue(text.contains("Contents: Read-only"))
    }

    @Test fun everyErrorHasAMessage() {
        listOf(
            UpdateError.NoToken, UpdateError.Network, UpdateError.NoRepoAccess, UpdateError.BadToken, UpdateError.NoRelease,
            UpdateError.ReleaseChanged, UpdateError.UpToDate, UpdateError.RateLimited(null), UpdateError.RateLimited(NOW),
            UpdateError.Server(500), UpdateError.Malformed("m"), UpdateError.Corrupt("c"), UpdateError.LowStorage(1, 2), UpdateError.Unexpected("u"),
        ).forEach { assertTrue(it.toString(), messages.message(it).isNotBlank()) }
        assertTrue(messages.message(UpdateError.RateLimited(NOW)).contains("14:05"))
        assertTrue(messages.message(UpdateError.LowStorage(21_400_000, 3_000_000)).contains("21.4 MB"))
        assertTrue(messages.message(UpdateError.LowStorage(21_400_000, -1)).contains("unknown"))
    }

    @Test fun megabytes() {
        assertEquals("0.0 MB", UpdateMessages.megabytes(0))
        assertEquals("0.0 MB", UpdateMessages.megabytes(-5))
        assertEquals("0.7 MB", UpdateMessages.megabytes(704_244))
        assertEquals("1.0 MB", UpdateMessages.megabytes(999_999))
        assertEquals("9223372036854.8 MB", UpdateMessages.megabytes(Long.MAX_VALUE))
        assertEquals("0.1 MB", UpdateMessages.megabytes(50_000))
        assertEquals("0.0 MB", UpdateMessages.megabytes(49_999))
    }
}
