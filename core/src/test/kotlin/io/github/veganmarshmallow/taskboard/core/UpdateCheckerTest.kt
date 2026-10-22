package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.update.UpdateError
import io.github.veganmarshmallow.taskboard.core.update.UpdateResult
import io.github.veganmarshmallow.taskboard.core.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UpdateCheckerTest {
    private val apk = fakeApk()

    private fun check(http: FakeStreamingHttp) = Updater(http, TOKEN, FIXED_CLOCK).check()

    private fun UpdateResult<*>.error() = (this as UpdateResult.Err).error

    @Test fun readsVersionJsonFromTheLatestRelease() {
        val http = FakeStreamingHttp().standard(apk, code = 42)
        val update = (check(http) as UpdateResult.Ok).value
        assertEquals(42L, update.version.versionCode)
        assertEquals("v0.1.42", update.version.label)
        assertEquals(sha256(apk), update.version.apkSha256)
        assertEquals(APK_ID, update.apk.id)
        assertTrue(update.isNewerThan(41))
        assertFalse(update.isNewerThan(42))
        assertEquals(listOf(LATEST_URL, assetUrl(VERSION_ID), "$STORAGE-v"), http.requests.map { it.url })
        assertEquals(http.opened, http.closed) // every response closed
    }

    @Test fun sendsTheTokenOnlyToApiGithubCom() {
        val http = FakeStreamingHttp().standard(apk)
        check(http)
        http.requests.forEach { r ->
            val hasToken = r.headers["Authorization"] == "Bearer $TOKEN"
            assertEquals(r.url, r.url.startsWith("https://api.github.com/"), hasToken)
        }
        assertEquals("application/vnd.github+json", http.requests[0].headers["Accept"])
        assertEquals("application/octet-stream", http.requests[1].headers["Accept"])
    }

    @Test fun fallsBackToReleaseNotesWithoutVersionJson() {
        val http = FakeStreamingHttp().standard(apk, version = null)
        val update = (check(http) as UpdateResult.Ok).value
        assertEquals(9L, update.version.versionCode) // from "versionCode 9" in the notes
        assertEquals(null, update.version.apkSha256)
        assertEquals(1, http.requests.size)
    }

    @Test fun noVersionAnywhereIsMalformed() {
        val http = FakeStreamingHttp()
            .on(LATEST_URL) { streamed(200, releaseJson(assetJson(APK_ID, "taskboard-widget.apk", 10), notes = "no info")) }
        assertTrue(check(http).error() is UpdateError.Malformed)
    }

    @Test fun releaseWithoutApkIsNoRelease() {
        val http = FakeStreamingHttp().on(LATEST_URL) { streamed(200, releaseJson(assetJson(5, "1-setup.png", 10))) }
        assertEquals(UpdateError.NoRelease, check(http).error())
    }

    @Test fun releaseWithEmptyAssetsIsNoRelease() {
        val http = FakeStreamingHttp().on(LATEST_URL) { streamed(200, releaseJson()) }
        assertEquals(UpdateError.NoRelease, check(http).error())
    }

    @Test fun release404WithReadableRepoIsNoRelease() {
        val http = FakeStreamingHttp()
            .on(LATEST_URL) { streamed(404, """{"message":"Not Found"}""") }
            .on(PROBE_URL) { streamed(200, "[]") }
        assertEquals(UpdateError.NoRelease, check(http).error())
        assertEquals(listOf(LATEST_URL, PROBE_URL), http.requests.map { it.url })
        assertEquals(http.opened, http.closed)
    }

    @Test fun release404WithUnreadableRepoIsMissingTokenAccess() {
        // A fine-grained token without Taskboard-widget sees the private repo as 404.
        val http = FakeStreamingHttp()
            .on(LATEST_URL) { streamed(404, """{"message":"Not Found"}""") }
            .on(PROBE_URL) { streamed(404, """{"message":"Not Found"}""") }
        assertEquals(UpdateError.NoRepoAccess, check(http).error())
    }

    @Test fun release403IsMissingTokenAccess() {
        val http = FakeStreamingHttp().on(LATEST_URL) {
            streamed(403, """{"message":"Resource not accessible by personal access token"}""")
        }
        assertEquals(UpdateError.NoRepoAccess, check(http).error())
    }

    @Test fun probe403IsMissingTokenAccess() {
        val http = FakeStreamingHttp()
            .on(LATEST_URL) { streamed(404, "{}") }
            .on(PROBE_URL) { streamed(403, """{"message":"Resource not accessible by personal access token"}""") }
        assertEquals(UpdateError.NoRepoAccess, check(http).error())
    }

    @Test fun versionJson403And404() {
        val forbidden = FakeStreamingHttp().standard(apk).on(assetUrl(VERSION_ID)) { streamed(403, "{}") }
        assertEquals(UpdateError.NoRepoAccess, check(forbidden).error())
        val gone = FakeStreamingHttp().standard(apk).on(assetUrl(VERSION_ID)) { streamed(404, "{}") }
        assertEquals(UpdateError.ReleaseChanged, check(gone).error())
    }

    @Test fun badTokenAndRateLimitAndServerErrors() {
        assertEquals(UpdateError.BadToken, check(FakeStreamingHttp().on(LATEST_URL) { streamed(401, "{}") }).error())
        val limited = check(FakeStreamingHttp().on(LATEST_URL) {
            streamed(403, "{}", "x-ratelimit-remaining" to "0", "x-ratelimit-reset" to (NOW.epochSecond + 600).toString())
        }).error()
        assertEquals(UpdateError.RateLimited(NOW.plusSeconds(600)), limited)
        assertEquals(UpdateError.RateLimited(null), check(FakeStreamingHttp().on(LATEST_URL) { streamed(429, "{}") }).error())
        assertEquals(UpdateError.RateLimited(null), check(FakeStreamingHttp().on(LATEST_URL) { streamed(403, """{"message":"You have exceeded a secondary rate limit"}""") }).error())
        assertEquals(UpdateError.Server(502), check(FakeStreamingHttp().on(LATEST_URL) { streamed(502, "<html>") }).error())
        assertTrue(check(FakeStreamingHttp().on(LATEST_URL) { streamed(418, "") }).error() is UpdateError.Unexpected)
    }

    @Test fun networkFailures() {
        assertEquals(UpdateError.Network, check(FakeStreamingHttp()).error()) // no route -> IOException
        val dropped = FakeStreamingHttp().on(LATEST_URL) {
            io.github.veganmarshmallow.taskboard.core.http.StreamingResponse(200, emptyMap(), DroppingStream("{\"assets\":".toByteArray()))
        }
        assertEquals(UpdateError.Network, check(dropped).error())
        val storageDown = FakeStreamingHttp().standard(apk).on("$STORAGE-v") { throw IOException("dns") }
        assertEquals(UpdateError.Network, check(storageDown).error())
    }

    @Test fun runtimeExceptionsDoNotEscapeAndDoNotLeakTheToken() {
        val http = FakeStreamingHttp().on(LATEST_URL) { throw SecurityException("Bearer $TOKEN") }
        val error = check(http).error() as UpdateError.Unexpected
        assertFalse(error.detail.contains(TOKEN))
    }

    @Test fun malformedReleaseAndVersionJson() {
        assertTrue(check(FakeStreamingHttp().on(LATEST_URL) { streamed(200, "<html>oops</html>") }).error() is UpdateError.Malformed)
        assertTrue(check(FakeStreamingHttp().on(LATEST_URL) { streamed(200, "") }).error() is UpdateError.Malformed)
        val badVersion = FakeStreamingHttp().standard(apk).on("$STORAGE-v") { streamed(200, """{"versionCode":"x"}""") }
        assertTrue(check(badVersion).error() is UpdateError.Malformed)
    }

    @Test fun hugeReleaseBodyIsRejectedWithoutReadingItAll() {
        val huge = ByteArray(3 * 1024 * 1024) { ' '.code.toByte() }
        assertTrue(check(FakeStreamingHttp().on(LATEST_URL) { streamed(200, huge) }).error() is UpdateError.Malformed)
    }

    @Test fun hebrewAndUnicodeInReleaseNotesAreFine() {
        val http = FakeStreamingHttp().on(LATEST_URL) {
            streamed(200, releaseJson(assetJson(APK_ID, "taskboard-widget.apk", 10), notes = "גרסה חדשה 🎉 versionCode 77 ‏"))
        }
        assertEquals(77L, (check(http) as UpdateResult.Ok).value.version.versionCode)
    }

    @Test fun redirectLoopAndUnsafeRedirectsStop() {
        val loop = FakeStreamingHttp().on(LATEST_URL) { streamed(302, ByteArray(0), "Location" to LATEST_URL) }
        assertTrue(check(loop).error() is UpdateError.Unexpected)
        assertEquals(6, loop.requests.size) // first + MAX_HOPS
        assertEquals(loop.opened, loop.closed)
        val downgrade = FakeStreamingHttp().on(LATEST_URL) { streamed(302, ByteArray(0), "Location" to "http://evil.example/") }
        assertTrue(check(downgrade).error() is UpdateError.Unexpected)
        assertEquals(1, downgrade.requests.size)
        val noLocation = FakeStreamingHttp().on(LATEST_URL) { streamed(302) }
        assertTrue(check(noLocation).error() is UpdateError.Unexpected)
    }

    @Test fun storage403MeansTheLinkExpired() {
        val http = FakeStreamingHttp().standard(apk).on("$STORAGE-v") { streamed(403, "<Error>AccessDenied</Error>") }
        assertEquals(UpdateError.ReleaseChanged, check(http).error())
    }
}
