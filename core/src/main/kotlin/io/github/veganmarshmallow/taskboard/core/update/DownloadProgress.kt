package io.github.veganmarshmallow.taskboard.core.update

/** Download progress for the UI. [total] is the expected size in bytes (always known from the release). */
data class DownloadProgress(val downloaded: Long, val total: Long) {
    /** 0..100, never above 100 even if the server sends more than promised. */
    val percent: Int get() = if (total <= 0) 0 else ((downloaded.coerceIn(0, total) * 100) / total).toInt()
}
