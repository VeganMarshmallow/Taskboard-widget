package io.github.veganmarshmallow.taskboard.update

import android.content.Context
import android.os.storage.StorageManager
import io.github.veganmarshmallow.taskboard.core.update.DownloadTarget
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Downloads into `cache/updates/`: bytes go to a `.part` file, which is renamed to the final APK
 * only after core has verified it, so the installer never sees a partial or unverified file.
 */
class CacheDownloadTarget(
    private val dir: File,
    private val freeSpace: (File) -> Long = { it.usableSpace },
) : DownloadTarget {
    val apk = File(dir, APK_NAME)
    private val part = File(dir, "$APK_NAME.part")

    override fun availableBytes(): Long {
        dir.mkdirs()
        return freeSpace(dir).takeIf { it > 0 } ?: -1 // 0 also means "couldn't tell"
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

        fun of(context: Context): CacheDownloadTarget {
            val storage = context.getSystemService(StorageManager::class.java)
            return CacheDownloadTarget(dir(context)) { file -> allocatable(storage, file) }
        }

        /** Free space including cache Android would clear for us; falls back to usableSpace. */
        private fun allocatable(storage: StorageManager?, file: File): Long = try {
            storage?.let { it.getAllocatableBytes(it.getUuidForPath(file)) } ?: file.usableSpace
        } catch (e: IOException) {
            file.usableSpace
        }
    }
}
