package io.github.veganmarshmallow.taskboard.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** The "install unknown apps" permission, and the intent that opens Android's package installer. */
class ApkInstaller(private val context: Context) {
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Settings → Install unknown apps → this app. */
    fun permissionSettings(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun install(): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(ApkProvider.uri(context), ApkProvider.MIME)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
}
