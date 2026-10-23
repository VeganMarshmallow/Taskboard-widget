package io.github.veganmarshmallow.taskboard.update

import android.content.Context

/** Whether the widget header shows the "update available" dot. Reads only the cached check. */
object UpdateBadge {
    fun isShown(context: Context): Boolean =
        UpdateStore(context).load().offer(InstalledVersion.of(context).versionCode) != null
}
