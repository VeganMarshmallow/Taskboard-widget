package io.github.veganmarshmallow.taskboard

import io.github.veganmarshmallow.taskboard.core.update.AvailableUpdate
import io.github.veganmarshmallow.taskboard.core.update.ReleaseAsset
import io.github.veganmarshmallow.taskboard.core.update.UpdateStatus
import io.github.veganmarshmallow.taskboard.core.update.VersionInfo

/** Cached update checks for view tests and screenshots. The installed test build has versionCode 1. */
object FakeUpdates {
    val V57 = AvailableUpdate(
        VersionInfo(57, "0.1.57", "776dc5f6623ab408acd13439f7f081a22c508fd4", "a".repeat(64), 712_448),
        ReleaseAsset(111, "taskboard-widget.apk", 712_448, "https://api.github.com/repos/VeganMarshmallow/Taskboard-widget/releases/assets/111"),
    )

    fun available() = UpdateStatus(FakeBoards.NOW, V57)
}
