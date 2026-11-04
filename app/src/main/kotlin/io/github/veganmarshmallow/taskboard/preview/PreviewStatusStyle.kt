package io.github.veganmarshmallow.taskboard.preview

import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.preview.PreviewStatus

/**
 * The status chip's tint for each status (#46): a soft container shade of the status hue behind the
 * theme's normal text, so the chip reads calmly and the text always has contrast. Themes that
 * outline the chip instead (`tbTintsStatus` false) don't use it.
 */
object PreviewStatusStyle {
    fun container(status: PreviewStatus): Int = when (status) {
        PreviewStatus.NEEDS_YOU -> R.color.status_needs_you_container
        PreviewStatus.IN_REVIEW -> R.color.status_review_container
        PreviewStatus.IN_PROGRESS -> R.color.status_progress_container
        PreviewStatus.BLOCKED, PreviewStatus.BLOCKED_ON_YOU -> R.color.status_blocked_container
        PreviewStatus.TODO, PreviewStatus.CLOSED -> R.color.status_todo_container
        PreviewStatus.DONE -> R.color.status_done_container
    }
}
