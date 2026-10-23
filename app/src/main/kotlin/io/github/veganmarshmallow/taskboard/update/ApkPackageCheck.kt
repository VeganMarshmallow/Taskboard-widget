package io.github.veganmarshmallow.taskboard.update

import android.content.Context
import io.github.veganmarshmallow.taskboard.core.update.ApkIdentityCheck
import io.github.veganmarshmallow.taskboard.core.update.AvailableUpdate
import io.github.veganmarshmallow.taskboard.core.update.PackageCheck
import io.github.veganmarshmallow.taskboard.core.update.UpdateError
import java.io.File

/**
 * Compares the downloaded APK ([apk], the verified partial file) with the installed app: same
 * package, the promised versionCode, and the installed app's signing certificate. Decisions are in
 * core's [ApkIdentityCheck]; this only reads the two identities from PackageManager.
 */
class ApkPackageCheck(private val context: Context, private val apk: File) : PackageCheck {
    override fun verify(update: AvailableUpdate): UpdateError? {
        val pm = context.packageManager
        return ApkIdentityCheck.check(
            installed = ApkSigners.installed(pm, context.packageName),
            archive = ApkSigners.archive(pm, apk),
            expectedVersionCode = update.version.versionCode,
        )
    }
}
