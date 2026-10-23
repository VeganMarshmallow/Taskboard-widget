package io.github.veganmarshmallow.taskboard.core.update

/** Android installs an APK over the current one only when its versionCode is higher. */
object VersionComparison {
    fun isNewer(available: VersionInfo, installedVersionCode: Long): Boolean = available.versionCode > installedVersionCode
}
