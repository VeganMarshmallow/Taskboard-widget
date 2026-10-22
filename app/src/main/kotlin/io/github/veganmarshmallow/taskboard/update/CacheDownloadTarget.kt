package io.github.veganmarshmallow.taskboard.update

import android.content.Context
import io.github.veganmarshmallow.taskboard.core.update.DownloadTarget
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Downloads into `cache/updates/`: bytes go to a `.part` file, which is renamed to the final APK
 * only after core has verified it, so the installer never sees a partial or unverified file.
 */
class CacheDownloadTarget(private val dir: File) : DownloadTarget {
    val apk = File(dir, APK_NAME)
    private val part = File(dir, "$APK_NAME.part")

    override fun availableBytes(): Long {
        dir.mkdirs()
        return dir.usableSpace.takeIf { it > 0 } ?: -1 // 0 also means "couldn't tell"
    }

    override fun open(): OutputStream {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.name}")
        apk.delete()
        return FileOutputStream(part)
    }

    override fun commit() {
        if (!part.renameTo(apk)) throw IOException("cannot rename the download")
    }

    override fun discard() {
        part.delete()
    }

    /** Deletes any downloaded or partial APK (after the update is installed). */
    fun clear() {
        part.delete()
        apk.delete()
    }

    companion object {
        const val APK_NAME = "taskboard-widget.apk"

        fun dir(context: Context) = File(context.cacheDir, "updates")

        fun of(context: Context) = CacheDownloadTarget(dir(context))
    }
}
