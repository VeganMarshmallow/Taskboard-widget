package io.github.veganmarshmallow.taskboard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.update.ApkIdentityCheck
import io.github.veganmarshmallow.taskboard.core.update.AvailableUpdate
import io.github.veganmarshmallow.taskboard.core.update.UpdateError
import io.github.veganmarshmallow.taskboard.update.ApkPackageCheck
import io.github.veganmarshmallow.taskboard.update.ApkSigners
import io.github.veganmarshmallow.taskboard.update.CacheDownloadTarget
import io.github.veganmarshmallow.taskboard.update.InstalledVersion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The downloaded APK is compared with the installed app's real signing certificate, read from
 * PackageManager (GET_SIGNING_CERTIFICATES on API 28+, GET_SIGNATURES below).
 */
@RunWith(AndroidJUnit4::class)
class UpdateSignatureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val pm = context.packageManager
    private val target = CacheDownloadTarget.of(context)
    private val installedCode = InstalledVersion.of(context).versionCode

    @After fun cleanUp() = target.clear()

    private fun updateWithCode(code: Long): AvailableUpdate =
        FakeUpdates.V57.copy(version = FakeUpdates.V57.version.copy(versionCode = code))

    /** Puts [source] where a verified download waits for the package check. */
    private fun stage(source: File): File {
        target.open().use { out -> source.inputStream().use { it.copyTo(out) } }
        return target.part
    }

    @Test fun installedAppHasAReadableSigningCertificate() {
        val installed = ApkSigners.installed(pm, context.packageName)!!
        assertEquals(context.packageName, installed.packageName)
        assertEquals(installedCode, installed.versionCode)
        assertTrue(installed.signers.isNotEmpty())
        installed.signers.forEach { assertTrue(it, Regex("[0-9a-f]{64}").matches(it)) }
        assertTrue(installed.signerHistory.containsAll(installed.signers))
    }

    @Test fun theInstalledApkFileHasTheSameIdentityAsTheInstalledApp() {
        val installed = ApkSigners.installed(pm, context.packageName)!!
        val archive = ApkSigners.archive(pm, File(context.packageCodePath))!!
        assertEquals(installed.signers, archive.signers)
        assertNull(ApkIdentityCheck.check(installed, archive, installedCode))
    }

    @Test fun packageCheckAcceptsABuildOfThisAppSignedWithItsKey() {
        val part = stage(File(context.packageCodePath))
        assertNull(ApkPackageCheck(context, part).verify(updateWithCode(installedCode)))
    }

    @Test fun packageCheckRejectsAVersionCodeOtherThanPromised() {
        val part = stage(File(context.packageCodePath))
        assertTrue(ApkPackageCheck(context, part).verify(updateWithCode(installedCode + 1)) is UpdateError.Corrupt)
    }

    @Test fun packageCheckRejectsAnotherApp() {
        // The instrumentation APK is a real, signed APK of a different package.
        val part = stage(File(instrumentation.context.packageCodePath))
        val error = ApkPackageCheck(context, part).verify(updateWithCode(installedCode))
        assertTrue(error.toString(), error is UpdateError.Untrusted)
    }

    @Test fun packageCheckRejectsADifferentSigningKey() {
        val installed = ApkSigners.installed(pm, context.packageName)!!
        val archive = ApkSigners.archive(pm, File(context.packageCodePath))!!
        val reSigned = archive.copy(signers = setOf("0".repeat(64)), signerHistory = setOf("0".repeat(64)))
        val error = ApkIdentityCheck.check(installed, reSigned, installedCode)
        assertTrue(error.toString(), error is UpdateError.Untrusted)
    }

    @Test fun packageCheckRejectsAFileAndroidCannotParse() {
        val junk = File(context.cacheDir, "junk.apk").apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(4096) { 7 }) }
        try {
            val part = stage(junk)
            assertTrue(ApkPackageCheck(context, part).verify(updateWithCode(installedCode)) is UpdateError.Corrupt)
        } finally {
            junk.delete()
        }
        assertTrue(ApkPackageCheck(context, File(context.cacheDir, "missing.apk")).verify(updateWithCode(installedCode)) is UpdateError.Corrupt)
    }
}
