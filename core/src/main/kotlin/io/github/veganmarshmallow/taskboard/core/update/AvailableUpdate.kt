package io.github.veganmarshmallow.taskboard.core.update

/**
 * What the latest release offers: its version, and the release page ([pageUrl], always a
 * [ReleasePage.isSafe] URL) where the APK is downloaded in the browser.
 */
data class AvailableUpdate(val version: VersionInfo, val pageUrl: String = ReleasePage.FALLBACK) {
    fun isNewerThan(installedVersionCode: Long) = VersionComparison.isNewer(version, installedVersionCode)
}
