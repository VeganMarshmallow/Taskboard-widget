package io.github.veganmarshmallow.taskboard.core.error

/**
 * What the device says about its network, asked right after a request fails to connect. It lets
 * [ErrorClassifier.classifyFailure] tell "No network" from "network up, GitHub unreachable" for
 * every request, not only the widget's refresh (#35). The app implements it with Android's
 * ConnectivityManager.
 */
fun interface NetworkProbe {
    /** Whether the device has an internet-capable network (a VPN counts). Must not throw. */
    fun deviceOnline(): Boolean
}
