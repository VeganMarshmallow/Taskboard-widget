package io.github.veganmarshmallow.taskboard.core.update

import java.security.MessageDigest

/**
 * Checks a finished download against what the release promised: the exact byte count, the
 * SHA-256 from version.json, and the ZIP signature every APK starts with.
 */
class DownloadVerifier(val expectedSize: Long, private val expectedSha256: String) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val head = ByteArray(4)
    private var count = 0L

    fun update(buffer: ByteArray, length: Int) {
        digest.update(buffer, 0, length)
        var i = 0
        while (count + i < head.size && i < length) {
            head[(count + i).toInt()] = buffer[i]
            i++
        }
        count += length
    }

    val bytes: Long get() = count

    /** Null when everything matches, otherwise why not. Call once, after the last [update]. */
    fun problem(): String? {
        if (count < expectedSize) return "download incomplete ($count of $expectedSize bytes)"
        if (count > expectedSize) return "download larger than expected ($count of $expectedSize bytes)"
        if (!head.contentEquals(ZIP_MAGIC)) return "the file is not an APK"
        if (Sha256.hex(digest.digest()) != expectedSha256.lowercase()) return "SHA-256 mismatch"
        return null
    }

    private companion object {
        val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    }
}
