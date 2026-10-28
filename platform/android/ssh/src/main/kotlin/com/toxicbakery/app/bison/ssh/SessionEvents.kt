package com.toxicbakery.app.bison.ssh

import kotlin.time.Duration

/**
 * Receives the events of the reconnect loop of an [SshBackend]. [SshTransportManager] gives its receiver to the
 * backend with [SshBackend.setSessionEvents].
 *
 * A [SessionSupervisor] sends the events of one loop for one machine in this order: [onReconnecting] one time,
 * [onReconnectFailed] zero or more times, and then [onReconnected] or [onNeedsUser] one time.
 *
 * The functions must not throw, because the loop does not catch their errors.
 */
interface SessionEvents {

    /** The session of [machineId] closed, and the reconnect loop starts. [cause] tells why, or is null. */
    fun onReconnecting(machineId: String, cause: Throwable?)

    /**
     * The attempt [failures] of the loop for [machineId] failed with the network error [result]. The next attempt
     * starts after [nextDelay].
     */
    fun onReconnectFailed(machineId: String, failures: Int, result: SshAuthResult.NetworkError, nextDelay: Duration)

    /** The loop opened a new session for [machineId]. */
    suspend fun onReconnected(machineId: String)

    /**
     * The loop of [machineId] stopped, because [result] is not a network error. The user must do an action before
     * the machine can connect again.
     */
    suspend fun onNeedsUser(machineId: String, result: SshAuthResult)
}
