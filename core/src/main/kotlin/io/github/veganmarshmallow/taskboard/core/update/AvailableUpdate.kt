package io.github.veganmarshmallow.taskboard.core.update

/** What the latest release offers: its version, and the APK asset to download. */
data class AvailableUpdate(val version: VersionInfo, val apk: ReleaseAsset) {
    fun isNewerThan(installedVersionCode: Long) = VersionComparison.isNewer(version, installedVersionCode)
}
