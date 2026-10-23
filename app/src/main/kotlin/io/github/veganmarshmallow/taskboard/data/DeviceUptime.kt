package io.github.veganmarshmallow.taskboard.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import io.github.veganmarshmallow.taskboard.core.work.Uptime
import io.github.veganmarshmallow.taskboard.core.work.UptimeInstant

/**
 * The monotonic clock for refresh staleness: elapsedRealtime (counts deep sleep, never jumps with
 * the wall clock) plus the boot count (API 24+), so a mark left by a reboot mid-refresh is known
 * to be dead at once. The boot count is fixed for the life of a process, so it is read once.
 */
class DeviceUptime(context: Context) : Uptime {
    private val boot: Int by lazy { readBootCount(context.applicationContext) }

    override fun now() = UptimeInstant(boot, SystemClock.elapsedRealtime())

    private fun readBootCount(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, UptimeInstant.UNKNOWN_BOOT)
    } catch (e: RuntimeException) {
        UptimeInstant.UNKNOWN_BOOT // some builds restrict settings reads; elapsedRealtime still catches most reboots
    }
}
