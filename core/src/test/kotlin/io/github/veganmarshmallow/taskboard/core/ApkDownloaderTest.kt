package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.http.StreamingResponse
import io.github.veganmarshmallow.taskboard.core.update.AvailableUpdate
import io.github.veganmarshmallow.taskboard.core.update.DownloadProgress
import io.github.veganmarshmallow.taskboard.core.update.ReleaseAsset
import io.github.veganmarshmallow.taskboard.core.update.StoragePolicy
import io.github.veganmarshmallow.taskboard.core.update.UpdateError
import io.github.veganmarshmallow.taskboard.core.update.UpdateResult
import io.github.veganmarshmallow.taskboard.core.update.Updater
import io.github.veganmarshmallow.taskboard.core.update.VersionInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ApkDownloaderTest {
    private val apk = fakeApk(200_000)

    private fun update(bytes: ByteArray = apk, sha: String? = sha256(bytes), size: Long? = bytes.size.toLong(), assetSize: Long = bytes.size.toLong()) =
        AvailableUpdate(VersionInfo(42, "0.1.42", null, sha, size), ReleaseAsset(APK_ID, "taskboard-widget.apk", assetSize, assetUrl(APK_ID)))

    private fun download(http: FakeStreamingHttp, target: MemoryTarget, u: AvailableUpdate = update(), progress: MutableList<DownloadProgress>? = null) =
        Updater(http, TOKEN, FIXED_CLOCK).download(u, target, TRUSTED, { progress?.add(it) })

    private fun UpdateResult<*>.error() = (this as UpdateResult.Err).error

    @Test fun downloadsVerifiesAndCommits() {
        val http = FakeStreamingHttp().standard(apk)
        val target = MemoryTarget()
        val progress = mutableListOf<DownloadProgress>()
        assertEquals(UpdateResult.Ok(Unit), download(http, target, progress = progress))
        assertArrayEquals(apk, target.committed)
        assertEquals(0, target.discards)
        assertTrue(target.streamClosed)
        assertEquals(DownloadProgress(apk.size.toLong(), apk.size.toLong()), progress.last())
        assertEquals(100, progress.last().percent)
        assertTrue(progress.zipWithNext().all { (a, b) -> b.downloaded > a.downloaded })
        assertEquals(http.opened, http.closed)
    }

    @Test fun followsTheRedirectWithoutTheToken() {
        val http = FakeStreamingHttp().standard(apk)
        download(http, MemoryTarget())
        val (api, storage) = http.requests
        assertEquals(assetUrl(APK_ID), api.url)
        assertEquals("Bearer $TOKEN", api.headers["Authorization"])
        assertEquals("application/octet-stream", api.headers["Accept"])
        assertEquals(STORAGE, storage.url)
        assertNull(storage.headers["Authorization"])
        assertFalse(storage.headers.values.any { TOKEN in it })
    }

    @Test fun refusesToDownloadWithoutAChecksumOrSize() {
        // E.g. an update cached by an older build, before version.json was required.
        for (u in listOf(update(sha = null), update(size = null), update(sha = null, size = null))) {
            val http = FakeStreamingHttp().standard(apk)
            val target = MemoryTarget()
            assertTrue(download(http, target, u).error() is UpdateError.Malformed)
            assertTrue(http.requests.isEmpty())
            assertNull(target.committed)
        }
    }

    @Test fun truncatedDownloadIsCorruptAndDiscarded() {
        val http = FakeStreamingHttp().standard(apk).on(STORAGE) { streamed(200, apk.copyOf(apk.size - 1)) }
        val target = MemoryTarget()
        val error = download(http, target).error() as UpdateError.Corrupt
        assertTrue(error.detail.contains("incomplete"))
        assertNull(target.committed)
        assertEquals(1, target.discards)
    }

    @Test fun emptyBodyIsCorrupt() {
        val http = FakeStreamingHttp().standard(apk).on(STORAGE) { streamed(200, ByteArray(0)) }
        assertTrue(download(http, MemoryTarget()).error() is UpdateError.Corrupt)
        val noBody = FakeStreamingHttp().standard(apk).on(STORAGE) { StreamingResponse(200) }
        assertTrue(download(noBody, MemoryTarget()).error() is UpdateError.Corrupt)
    }

    @Test fun longerThanPromisedIsCorruptAndStopsReading() {
        val big = apk + ByteArray(10_000_000)
        val http = FakeStreamingHttp().standard(apk).on(STORAGE) { streamed(200, big) }
        val target = MemoryTarget()
        val error = download(http, target).error() as UpdateError.Corrupt
        assertTrue(error.detail.contains("larger"))
        assertTrue(target.partial == null) // discarded
    }

    @Test fun checksumMismatchIsCorrupt() {
        val tampered = apk.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        val http = FakeStreamingHttp().standard(apk).on(STORAGE) { streamed(200, tampered) }
        val error = download(http, MemoryTarget()).error() as UpdateError.Corrupt
        assertEquals("SHA-256 mismatch", error.detail)
    }

    @Test fun upperCaseChecksumStillMatches() {
        val target = MemoryTarget()
        assertEquals(UpdateResult.Ok(Unit), download(FakeStreamingHttp().standard(apk), target, update(sha = sha256(apk).uppercase())))
    }

    @Test fun notAnApkIsCorrupt() {
        val html = "<html>".toByteArray() + ByteArray(apk.size - 6)
        val http = FakeStreamingHttp().standard(apk).on(STORAGE) { streamed(200, html) }
        val error = download(http, MemoryTarget(), update(bytes = html)).error() as UpdateError.Corrupt
        assertTrue(error.detail.contains("not an APK"))
    }

    @Test fun tinyApkSmallerThanTheZipHeaderIsCorrupt() {
        val tiny = byteArrayOf(0x50, 0x4B)
        val http = FakeStreamingHttp().standard(tiny)
        assertTrue(download(http, MemoryTarget(), update(bytes = tiny)).error() is UpdateError.Corrupt)
    }

    @Test fun droppedConnectionMidDownloadIsNetworkAndDiscarded() {
        val http = FakeStreamingHttp().standard(apk).on(STORAGE) { StreamingResponse(200, emptyMap(), DroppingStream(apk.copyOf(50_000))) }
        val target = MemoryTarget()
        assertEquals(UpdateError.Network, download(http, target).error())
        assertEquals(1, target.discards)
        assertNull(target.committed)
        assertEquals(http.opened, http.closed)
    }

    @Test fun noNetworkAtAll() {
        assertEquals(UpdateError.Network, download(FakeStreamingHttp(), MemoryTarget()).error())
    }

    @Test fun lowStorageIsCheckedBeforeDownloading() {
        val http = FakeStreamingHttp().standard(apk)
        val target = MemoryTarget(available = 1_000)
        val error = download(http, target).error() as UpdateError.LowStorage
        assertEquals(StoragePolicy.requiredBytes(apk.size.toLong()), error.neededBytes)
        assertEquals(1_000L, error.availableBytes)
        assertTrue(http.requests.isEmpty())
    }

    @Test fun unknownFreeSpaceDoesNotBlock() {
        val target = MemoryTarget(available = -1)
        assertEquals(UpdateResult.Ok(Unit), download(FakeStreamingHttp().standard(apk), target))
    }

    @Test fun diskFullWhileWritingIsLowStorage() {
        val target = MemoryTarget(failWriteAfter = 100_000, writeFailure = "write failed: ENOSPC (No space left on device)")
        assertTrue(download(FakeStreamingHttp().standard(apk), target).error() is UpdateError.LowStorage)
        assertEquals(1, target.discards)
    }

    @Test fun otherWriteFailuresAreUnexpected() {
        val target = MemoryTarget(failWriteAfter = 100_000, writeFailure = "EIO")
        assertTrue(download(FakeStreamingHttp().standard(apk), target).error() is UpdateError.Unexpected)
        assertTrue(download(FakeStreamingHttp().standard(apk), MemoryTarget(failOpen = true)).error() is UpdateError.Unexpected)
        val commit = MemoryTarget(failCommit = true)
        assertTrue(download(FakeStreamingHttp().standard(apk), commit).error() is UpdateError.Unexpected)
        assertEquals(1, commit.discards)
    }

    @Test fun releaseRepublishedBetweenCheckAndDownload() {
        val gone = FakeStreamingHttp().standard(apk).on(assetUrl(APK_ID)) { streamed(404, """{"message":"Not Found"}""") }
        assertEquals(UpdateError.ReleaseChanged, download(gone, MemoryTarget()).error())
        // version.json and the asset disagree on the size: they come from different builds.
        assertEquals(UpdateError.ReleaseChanged, download(FakeStreamingHttp().standard(apk), MemoryTarget(), update(size = 5)).error())
    }

    @Test fun assetAccessAndTokenErrors() {
        val forbidden = FakeStreamingHttp().standard(apk).on(assetUrl(APK_ID)) { streamed(403, """{"message":"Resource not accessible by personal access token"}""") }
        assertEquals(UpdateError.NoRepoAccess, download(forbidden, MemoryTarget()).error())
        val expired = FakeStreamingHttp().standard(apk).on(assetUrl(APK_ID)) { streamed(401, "{}") }
        assertEquals(UpdateError.BadToken, download(expired, MemoryTarget()).error())
        val signedExpired = FakeStreamingHttp().standard(apk).on(STORAGE) { streamed(403, "<Error/>") }
        assertEquals(UpdateError.ReleaseChanged, download(signedExpired, MemoryTarget()).error())
        val storage500 = FakeStreamingHttp().standard(apk).on(STORAGE) { streamed(503, "") }
        assertEquals(UpdateError.Server(503), download(storage500, MemoryTarget()).error())
    }

    @Test fun cancellationDiscards() {
        val target = MemoryTarget()
        val result = Updater(FakeStreamingHttp().standard(apk), TOKEN, FIXED_CLOCK).download(update(), target, TRUSTED, {}, { true })
        assertTrue(result.error() is UpdateError.Unexpected)
        assertEquals(1, target.discards)
    }

    @Test fun downloadLatestRechecksFirst() {
        val target = MemoryTarget()
        val result = Updater(FakeStreamingHttp().standard(apk, code = 50), TOKEN, FIXED_CLOCK).downloadLatest(49, target, TRUSTED)
        assertEquals(50L, (result as UpdateResult.Ok).value.version.versionCode)
        assertArrayEquals(apk, target.committed)
        val current = Updater(FakeStreamingHttp().standard(apk, code = 50), TOKEN, FIXED_CLOCK).downloadLatest(50, MemoryTarget(), TRUSTED)
        assertEquals(UpdateError.UpToDate, current.error())
        val noAccess = FakeStreamingHttp().on(LATEST_URL) { streamed(403, "{}") }
        assertEquals(UpdateError.NoRepoAccess, Updater(noAccess, TOKEN, FIXED_CLOCK).downloadLatest(1, MemoryTarget(), TRUSTED).error())
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesToSendTheTokenToANonApiAssetUrl() {
        val evil = AvailableUpdate(VersionInfo(2, "x", null, "a".repeat(64), 10), ReleaseAsset(1, "taskboard-widget.apk", 10, "https://evil.example/assets/1"))
        Updater(FakeStreamingHttp(), TOKEN, FIXED_CLOCK).download(evil, MemoryTarget(), TRUSTED)
    }

    @Test fun progressPercent() {
        assertEquals(0, DownloadProgress(0, 100).percent)
        assertEquals(50, DownloadProgress(50, 100).percent)
        assertEquals(100, DownloadProgress(150, 100).percent)
        assertEquals(0, DownloadProgress(-5, 100).percent)
        assertEquals(0, DownloadProgress(5, 0).percent)
        assertEquals(99, DownloadProgress(199_999_999, 200_000_000).percent) // no overflow
    }

    @Test fun storagePolicy() {
        assertEquals(10L * 1024 * 1024 + 700_000, StoragePolicy.requiredBytes(700_000))
        assertEquals(200_000_000L * 2, StoragePolicy.requiredBytes(200_000_000))
        assertNull(StoragePolicy.check(700_000, Long.MAX_VALUE))
        assertNull(StoragePolicy.check(700_000, -1))
        assertTrue(StoragePolicy.check(700_000, 0) != null)
        val need = StoragePolicy.requiredBytes(700_000)
        assertNull(StoragePolicy.check(700_000, need))
        assertTrue(StoragePolicy.check(700_000, need - 1) != null)
    }

    @Test fun runtimeExceptionFromAvailableBytesIsTreatedAsUnknown() {
        val target = object : io.github.veganmarshmallow.taskboard.core.update.DownloadTarget by MemoryTarget() {
            override fun availableBytes(): Long = throw IllegalStateException("statfs")
        }
        assertEquals(UpdateResult.Ok(Unit), Updater(FakeStreamingHttp().standard(apk), TOKEN, FIXED_CLOCK).download(update(), target, TRUSTED))
    }

    @Test fun ioExceptionFromTheStreamClosingIsHarmless() {
        val http = FakeStreamingHttp().standard(apk).on(STORAGE) {
            StreamingResponse(200, emptyMap(), object : java.io.ByteArrayInputStream(apk) {
                override fun close() = throw IOException("close failed")
            })
        }
        val target = MemoryTarget()
        assertEquals(UpdateResult.Ok(Unit), download(http, target))
    }
}
