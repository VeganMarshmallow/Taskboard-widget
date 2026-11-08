package io.github.veganmarshmallow.taskboard.core.automerge

import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.board.Section

/**
 * Whether a refresh should start an auto merge poll (#79): the setting is on, no Merge all run is
 * going on, and the refresh's own checks (#45) found a PR to merge next. Otherwise no extra request.
 */
object AutoMergeGate {
    fun shouldPoll(enabled: Boolean, mergeAllRunning: Boolean, board: Board?): Boolean =
        enabled && !mergeAllRunning && board?.section(Section.NEEDS_YOU)?.items.orEmpty().any { it.mergeLane?.isNext == true }
}
