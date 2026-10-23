package io.github.veganmarshmallow.taskboard.core.update

import java.security.MessageDigest

/** Lower-case hex SHA-256, shared by download verification and signing-certificate digests. */
object Sha256 {
    /** The SHA-256 of [bytes], as 64 lower-case hex digits. */
    fun of(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** [bytes] as lower-case hex. Locale-independent (no String.format). */
    fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return out.toString()
    }

    private const val DIGITS = "0123456789abcdef"
}
