package io.github.veganmarshmallow.taskboard.update

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * A minimal FileProvider for exactly one file: the downloaded update APK. It hands Android's
 * installer a `content://` URI (file:// URIs are refused since Android 7). Not exported; the
 * installer gets one-off read access through FLAG_GRANT_READ_URI_PERMISSION on the install intent.
 * Written by hand because the app has no androidx dependency.
 */
class ApkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = if (isApk(uri)) MIME else null

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val file = file(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) file.name else if (it == OpenableColumns.SIZE) file.length() else null })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        val file = file(uri) ?: throw FileNotFoundException("no downloaded update")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int =
        throw UnsupportedOperationException()

    private fun isApk(uri: Uri) = uri.path == "/${CacheDownloadTarget.APK_NAME}"

    private fun file(uri: Uri): File? {
        val context = context ?: return null
        if (!isApk(uri)) return null
        return CacheDownloadTarget.of(context).apk.takeIf { it.isFile }
    }

    companion object {
        const val MIME = "application/vnd.android.package-archive"

        fun authority(context: Context) = "${context.packageName}.updates"

        fun uri(context: Context): Uri = Uri.Builder().scheme("content").authority(authority(context))
            .path(CacheDownloadTarget.APK_NAME).build()
    }
}
