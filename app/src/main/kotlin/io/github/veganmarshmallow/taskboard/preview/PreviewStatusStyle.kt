package io.github.veganmarshmallow.taskboard.preview

import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.preview.PreviewStatus

/** The status chip's colour for each status. */
object PreviewStatusStyle {
    fun color(status: PreviewStatus): Int = when (status) {
        PreviewStatus.NEEDS_YOU -> R.color.status_needs_you
        PreviewStatus.IN_REVIEW -> R.color.status_review
        PreviewStatus.IN_PROGRESS -> R.color.status_progress
        PreviewStatus.BLOCKED -> R.color.status_blocked
        PreviewStatus.TODO, PreviewStatus.CLOSED -> R.color.status_todo
        PreviewStatus.DONE -> R.color.status_done
    }
}
