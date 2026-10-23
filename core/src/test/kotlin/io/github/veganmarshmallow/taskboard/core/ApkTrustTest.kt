package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.http.StreamingResponse
import io.github.veganmarshmallow.taskboard.core.update.ApkIdentity
import io.github.veganmarshmallow.taskboard.core.update.ApkIdentityCheck
import io.github.veganmarshmallow.taskboard.core.update.AvailableUpdate
import io.github.veganmarshmallow.taskboard.core.update.PackageCheck
import io.github.veganmarshmallow.taskboard.core.update.ReleaseAsset
import io.github.veganmarshmallow.taskboard.core.update.Sha256
import io.github.veganmarshmallow.taskboard.core.update.UpdateError
import io.github.veganmarshmallow.taskboard.core.update.UpdateResult
import io.github.veganmarshmallow.taskboard.core.update.Updater
import io.github.veganmarshmallow.taskboard.core.update.VersionInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The signing-certificate and package check, and how the downloader uses it. */
class ApkTrustTest {
    private val pkg = "io.github.veganmarshmallow.taskboard"
    private val ourKey = Sha256.of("our certificate".toByteArray())
    private val otherKey = Sha256.of("someone else's certificate".toByteArray())
    private val installed = ApkIdentity(pkg, 41, setOf(ourKey))
    private fun archive(packageName: String = pkg, code: Long = 42, signers: Set<String> = setOf(ourKey), history: Set<String> = signers) =
        ApkIdentity(packageName, code, signers, history)

    private fun check(archive: ApkIdentity?, installed: ApkIdentity? = this.installed) = ApkIdentityCheck.check(installed, archive, 42)

    // --- ApkIdentityCheck -------------------------------------------------------------------

    @Test fun sameKeySamePackagePromisedVersionIsTrusted() {
        assertNull(check(archive()))
    }

    @Test fun differentKeyIsRejected() {
        val e = check(archive(signers = setOf(otherKey))) as UpdateError.Untrusted
        assertTrue(e.detail.contains("different key"))
    }

    @Test fun differentPackageIsRejected() {
        // It would install as a second app next to this one, not as an update.
        assertTrue(check(archive(packageName = "com.evil.app")) is UpdateError.Untrusted)
        assertTrue(check(archive(packageName = "$pkg.debug")) is UpdateError.Untrusted)
        assertTrue(check(archive(packageName = pkg.uppercase())) is UpdateError.Untrusted)
    }

    @Test fun unsignedApkIsRejected() {
        assertTrue(check(archive(signers = emptySet())) is UpdateError.Untrusted)
    }

    @Test fun unreadableArchiveIsCorrupt() {
        assertTrue(check(null) is UpdateError.Corrupt)
    }

    @Test fun unreadableInstalledSignatureFailsClosed() {
        assertTrue(check(archive(), installed = null) is UpdateError.Untrusted)
        assertTrue(check(archive(), installed = installed.copy(signers = emptySet())) is UpdateError.Untrusted)
    }

    @Test fun versionCodeMustMatchVersionJson() {
        assertTrue(check(archive(code = 41)) is UpdateError.Corrupt)
        assertTrue(check(archive(code = 43)) is UpdateError.Corrupt)
    }

    @Test fun keyRotationFromTheInstalledKeyIsAccepted() {
        // Android 9+ v3 rotation: the new APK is signed by a new key whose lineage includes ours.
        assertNull(check(archive(signers = setOf(otherKey), history = setOf(ourKey, otherKey))))
        // A lineage that doesn't include our key is not enough.
        val third = Sha256.of("third".toByteArray())
        assertTrue(check(archive(signers = setOf(otherKey), history = setOf(third, otherKey))) is UpdateError.Untrusted)
    }

    @Test fun multipleSignersMustMatchExactly() {
        val both = setOf(ourKey, otherKey)
        assertNull(check(archive(signers = both), installed = installed.copy(signers = both, signerHistory = both)))
        // A superset or subset of signers is not the same signer set.
        assertTrue(check(archive(signers = both)) is UpdateError.Untrusted)
        assertTrue(check(archive(), installed = installed.copy(signers = both, signerHistory = both)) is UpdateError.Untrusted)
    }

    // --- Sha256 -----------------------------------------------------------------------------

    @Test fun sha256KnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.of(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.of("abc".toByteArray()))
        assertEquals("00ff10", Sha256.hex(byteArrayOf(0, -1, 16)))
        assertEquals("", Sha256.hex(ByteArray(0)))
    }

    @Test fun sha256IsLocaleIndependent() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG")) // Arabic-Indic digits with String.format
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.of("abc".toByteArray()))
            assertEquals(sha256(fakeApk()), Sha256.of(fakeApk()))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    // --- The downloader runs the package check ----------------------------------------------

    private val apk = fakeApk(100_000)
    private val update = AvailableUpdate(
        VersionInfo(42, "0.1.42", null, sha256(apk), apk.size.toLong()),
        ReleaseAsset(APK_ID, "taskboard-widget.apk", apk.size.toLong(), assetUrl(APK_ID)),
    )

    private fun download(check: PackageCheck, http: FakeStreamingHttp = FakeStreamingHttp().standard(apk), target: MemoryTarget = MemoryTarget()) =
        Updater(http, TOKEN, FIXED_CLOCK).download(update, target, check) to target

    @Test fun untrustedApkIsDiscardedNeverCommitted() {
        val (result, target) = download({ UpdateError.Untrusted("it is signed with a different key") })
        assertTrue((result as UpdateResult.Err).error is UpdateError.Untrusted)
        assertNull(target.committed)
        assertEquals(1, target.discards)
    }

    @Test fun packageCheckRunsAfterTheChecksumOnTheFullFile() {
        var sawBytes = -1
        val t = MemoryTarget()
        val (result, _) = download({ sawBytes = t.partial!!.size(); null }, target = t)
        assertEquals(UpdateResult.Ok(Unit), result)
        assertEquals(apk.size, sawBytes)
        assertArrayEquals(apk, t.committed)
    }

    @Test fun packageCheckIsSkippedWhenTheChecksumFails() {
        var called = false
        val tampered = apk.copyOf().also { it[500] = (it[500] + 1).toByte() }
        val http = FakeStreamingHttp().standard(apk).on(STORAGE) { streamed(200, tampered) }
        val (result, target) = download({ called = true; null }, http)
        assertTrue((result as UpdateResult.Err).error is UpdateError.Corrupt)
        assertTrue(!called)
        assertNull(target.committed)
    }

    @Test fun aThrowingPackageCheckFailsClosed() {
        val (result, target) = download({ throw IllegalStateException("PackageParser crashed") })
        assertTrue((result as UpdateResult.Err).error is UpdateError.Untrusted)
        assertNull(target.committed)
        assertEquals(1, target.discards)
    }

    @Test fun commitFailureAfterTheCheckDiscards() {
        val (result, target) = download({ null }, target = MemoryTarget(failCommit = true))
        assertTrue((result as UpdateResult.Err).error is UpdateError.Unexpected)
        assertEquals(1, target.discards)
    }

    // --- Edge cases around the release changing and the network ------------------------------

    @Test fun releaseRecreatedMidDownloadIsCaughtByTheChecksum() {
        // The storage link still serves bytes, but they are the next build's APK (same size).
        val next = fakeApk(100_000).also { it[99_999] = 7 }
        val http = FakeStreamingHttp().standard(apk).on(STORAGE) { streamed(200, next) }
        val (result, target) = download({ null }, http)
        assertEquals(UpdateError.Corrupt("SHA-256 mismatch"), (result as UpdateResult.Err).error)
        assertNull(target.committed)
    }

    @Test fun networkDropThenRetryStartsAFreshFile() {
        var attempt = 0
        val http = FakeStreamingHttp().standard(apk).on(STORAGE) {
            attempt++
            if (attempt == 1) StreamingResponse(200, emptyMap(), DroppingStream(apk.copyOf(30_000))) else streamed(200, apk)
        }
        val target = MemoryTarget()
        val updater = Updater(http, TOKEN, FIXED_CLOCK)
        assertEquals(UpdateError.Network, (updater.download(update, target, TRUSTED) as UpdateResult.Err).error)
        assertNull(target.committed)
        assertEquals(UpdateResult.Ok(Unit), updater.download(update, target, TRUSTED))
        assertArrayEquals(apk, target.committed) // not the 30 KB prefix plus the full file
    }

    @Test fun expiredTokenOnTheCheckIsBadTokenAndNothingIsDownloaded() {
        val http = FakeStreamingHttp().standard(apk).on(LATEST_URL) { streamed(401, """{"message":"Bad credentials"}""") }
        val target = MemoryTarget()
        val result = Updater(http, TOKEN, FIXED_CLOCK).downloadLatest(1, target, TRUSTED)
        assertEquals(UpdateError.BadToken, (result as UpdateResult.Err).error)
        assertEquals(1, http.requests.size)
        assertNull(target.partial)
    }

    @Test fun lowerOrEqualVersionIsNeverDownloaded() {
        for (installedCode in listOf(42L, 43L, Long.MAX_VALUE)) {
            val http = FakeStreamingHttp().standard(apk, code = 42)
            val result = Updater(http, TOKEN, FIXED_CLOCK).downloadLatest(installedCode, MemoryTarget(), TRUSTED)
            assertEquals(UpdateError.UpToDate, (result as UpdateResult.Err).error)
            assertTrue(http.requests.none { it.url == STORAGE })
        }
    }
}
