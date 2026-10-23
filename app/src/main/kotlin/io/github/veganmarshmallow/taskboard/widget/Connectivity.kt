package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Whether any network with internet is up right now. A VPN that does not pass traffic still counts; the timeouts handle that. */
object Connectivity {
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return try {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: SecurityException) {
            true // cannot tell: let the job's own network constraint decide
        }
    }
}
