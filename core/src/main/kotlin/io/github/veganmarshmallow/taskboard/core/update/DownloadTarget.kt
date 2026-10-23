package io.github.veganmarshmallow.taskboard.core.update

import java.io.IOException
import java.io.OutputStream

/** Where a download goes (the app's cache dir on Android; memory in tests). */
interface DownloadTarget {
    /** Free bytes on the target's volume. */
    fun availableBytes(): Long

    /** Starts a fresh partial file, replacing any earlier one. */
    @Throws(IOException::class)
    fun open(): OutputStream

    /** Makes the verified partial file the final one. */
    @Throws(IOException::class)
    fun commit()

    /** Deletes the partial file. Must not throw. */
    fun discard()
}
