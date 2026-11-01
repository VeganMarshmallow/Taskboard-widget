package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import io.github.veganmarshmallow.taskboard.core.error.NetworkProbe

/** Core's [NetworkProbe], answered by [Connectivity.isOnline] (which never throws). */
class ConnectivityProbe(context: Context) : NetworkProbe {
    private val app = context.applicationContext

    override fun deviceOnline(): Boolean = Connectivity.isOnline(app)
}
