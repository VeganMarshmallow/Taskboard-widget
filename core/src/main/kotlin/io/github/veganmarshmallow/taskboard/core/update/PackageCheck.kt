package io.github.veganmarshmallow.taskboard.core.update

/**
 * Checks the downloaded, checksum-verified APK before it is committed for the installer: on Android
 * it reads the file's package name, versionCode and signing certificates and compares them with the
 * installed app via [ApkIdentityCheck]. Returns null when the APK may be installed. Must not throw.
 */
fun interface PackageCheck {
    fun verify(update: AvailableUpdate): UpdateError?
}
