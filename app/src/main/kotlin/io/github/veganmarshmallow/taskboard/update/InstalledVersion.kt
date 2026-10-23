package io.github.veganmarshmallow.taskboard.update

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import io.github.veganmarshmallow.taskboard.core.update.UpdatePresenter.InstalledBuild

/** The versionCode and versionName of the build that is running. */
object InstalledVersion {
    fun of(context: Context): InstalledBuild {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = versionCode(info)
        return InstalledBuild(code, info.versionName ?: code.toString())
    }

    /** versionCode as a Long on every API level (longVersionCode is API 28+). */
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else legacyCode(info)

    @Suppress("DEPRECATION")
    private fun legacyCode(info: PackageInfo): Long = info.versionCode.toLong()
}
