package io.github.veganmarshmallow.taskboard.update

import android.annotation.SuppressLint
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import io.github.veganmarshmallow.taskboard.core.update.ApkIdentity
import io.github.veganmarshmallow.taskboard.core.update.Sha256
import java.io.File

/**
 * Reads [ApkIdentity] (package, versionCode, SHA-256 of each signing certificate) for the installed
 * app and for an APK file, with the API 28+ signing-info API and the older signatures field below it.
 * Every certificate is compared, not just the first (what lint's PackageManagerGetSignatures warns about).
 */
@SuppressLint("PackageManagerGetSignatures")
object ApkSigners {
    fun installed(pm: PackageManager, packageName: String): ApkIdentity? = try {
        identity(pm.getPackageInfo(packageName, flags()))
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun archive(pm: PackageManager, apk: File): ApkIdentity? = try {
        @Suppress("DEPRECATION") // the PackageInfoFlags overload is API 33+; this one still works there
        pm.getPackageArchiveInfo(apk.path, flags())?.let(::identity)
    } catch (e: RuntimeException) {
        null // a damaged archive can make the parser throw
    }

    @Suppress("DEPRECATION")
    private fun flags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    /** versionCode as a Long on every API level (longVersionCode is API 28+). */
    fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else legacyCode(info)

    private fun identity(info: PackageInfo): ApkIdentity {
        val code = versionCode(info)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo
                ?: return ApkIdentity(info.packageName, code, emptySet())
            val current = signing.apkContentsSigners.orEmpty().map { Sha256.of(it.toByteArray()) }.toSet()
            val history = if (signing.hasMultipleSigners()) current
            else signing.signingCertificateHistory.orEmpty().map { Sha256.of(it.toByteArray()) }.toSet() + current
            return ApkIdentity(info.packageName, code, current, history)
        }
        return ApkIdentity(info.packageName, code, legacySigners(info))
    }

    @Suppress("DEPRECATION")
    private fun legacyCode(info: PackageInfo): Long = info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun legacySigners(info: PackageInfo): Set<String> =
        info.signatures.orEmpty().map { Sha256.of(it.toByteArray()) }.toSet()
}
