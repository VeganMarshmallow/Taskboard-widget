package io.github.veganmarshmallow.taskboard.update

import android.content.Context
import io.github.veganmarshmallow.taskboard.core.update.UpdatePresenter.InstalledBuild

/** The versionCode and versionName of the build that is running. */
object InstalledVersion {
    fun of(context: Context): InstalledBuild {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = ApkSigners.versionCode(info)
        return InstalledBuild(code, info.versionName ?: code.toString())
    }
}
