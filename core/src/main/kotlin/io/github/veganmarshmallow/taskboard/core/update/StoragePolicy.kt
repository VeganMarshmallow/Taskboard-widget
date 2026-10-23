package io.github.veganmarshmallow.taskboard.core.update

/** How much free space a download needs: the APK, plus headroom for the installer's own copy. */
object StoragePolicy {
    private const val MIN_HEADROOM = 10L * 1024 * 1024

    fun requiredBytes(apkSize: Long): Long = apkSize + maxOf(apkSize, MIN_HEADROOM)

    /** Null when there is room; otherwise the error to show. A negative [available] means unknown. */
    fun check(apkSize: Long, available: Long): UpdateError.LowStorage? {
        if (available < 0) return null
        val needed = requiredBytes(apkSize)
        return if (available < needed) UpdateError.LowStorage(needed, available) else null
    }
}
