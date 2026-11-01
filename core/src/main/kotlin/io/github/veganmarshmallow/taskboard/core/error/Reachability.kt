package io.github.veganmarshmallow.taskboard.core.error

/**
 * Picks the honest wording for a failed request using what the device says about its network.
 *
 * An exception alone cannot tell "no network" from "network up, GitHub unreachable": with no
 * network at all Android usually fails the DNS lookup (UnknownHostException), exactly like a VPN
 * whose DNS is broken. So "No network" is shown only when the device really has no network, and a
 * connection failure on a working network becomes [BoardError.Unreachable].
 */
object Reachability {
    /**
     * [deviceOnline]: whether the device had an internet-capable network (a VPN counts) right after
     * the failure. Only connection-level errors change; everything else is returned as is.
     */
    fun refine(error: BoardError, deviceOnline: Boolean): BoardError = when {
        error == BoardError.Network && deviceOnline -> BoardError.Unreachable
        error == BoardError.Unreachable && !deviceOnline -> BoardError.Network
        // The connection dropped mid-request because the network went away.
        error == BoardError.Timeout && !deviceOnline -> BoardError.Network
        else -> error
    }
}
