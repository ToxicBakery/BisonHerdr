package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest
import kotlin.time.Duration

/**
 * Does the work of [SshTransportManager] that uses the connection to a machine.
 *
 * [SshTransportManager] keeps the connection state, the surface lease, and the prompt queue. It calls the
 * backend for each connection step. [RealSshBackend] is the only backend in release builds. Debug builds
 * also have a simulated backend for the machines that the fake server made.
 *
 * A backend with sessions that can close, such as [RealSshBackend], reconnects on its own. It sends the events of
 * its reconnect loop to the [SessionEvents] of [setSessionEvents]. The default functions of the reconnect part
 * apply to a backend with no network connection: it has no loop, and its session is always live.
 */
interface SshBackend {

    /**
     * Logs in to [machine] over [route]. Returns [SshAuthResult.Success], or the kind of the failure.
     */
    suspend fun authenticate(machine: HostMachine, route: ResolvedSshRoute): SshAuthResult

    /**
     * Selects the backbone for [machine]: Mosh UDP or the SSH endpoint bridge.
     * Returns null when no backbone that [HostMachine.transportMode] permits is available.
     */
    suspend fun negotiateBackbone(machine: HostMachine, route: ResolvedSshRoute): ActiveBackbone?

    /**
     * Publishes the first snapshot and surface of [machine] and starts the updates that follow.
     * The backend sends surface updates only while [isSurfaceActive] returns true.
     */
    suspend fun openSurface(
        machine: HostMachine,
        route: ResolvedSshRoute,
        backbone: ActiveBackbone,
        isSurfaceActive: () -> Boolean,
    )

    /**
     * Sends the Herdr RPC [request] to [machine]. [isMoshActive] is true when the backbone is Mosh UDP.
     * Returns the response from the machine, or null when the backend has no response.
     */
    suspend fun sendEndpointCommand(
        machine: HostMachine,
        route: ResolvedSshRoute,
        request: ClientShellEndpointRequest,
        isMoshActive: Boolean,
    ): String?

    /**
     * Sends one input [event] to [paneId] on [machine]. Returns false when the backend did not send it.
     */
    suspend fun sendInput(
        machine: HostMachine,
        route: ResolvedSshRoute,
        paneId: String,
        event: ClientPaneInputEvent,
    ): Boolean

    /**
     * Changes the terminal viewport of [machineId] to [cols] x [rows].
     */
    fun resize(machineId: String, cols: UShort, rows: UShort)

    /**
     * Closes the session of [machineId] and releases its resources.
     */
    fun disconnect(machineId: String)

    /** Sets the receiver of the events of the reconnect loop. The default backend has no loop and sends no events. */
    fun setSessionEvents(events: SessionEvents) = Unit

    /**
     * Returns [LivenessResult.Live] when the session of [machineId] replies in [timeout]. Returns
     * [LivenessResult.Dead] when it does not reply, or when the machine has no open session. The default backend has
     * no network connection, so its session is always live.
     */
    suspend fun checkLiveness(machineId: String, timeout: Duration): LivenessResult = LivenessResult.Live

    /**
     * Starts a reconnect attempt for [machineId] now, with no delay. [deadSessionId] is the session of a failed
     * liveness check ([LivenessResult.Dead.sessionId]). When a different session is live, the reconnect loop opened
     * it after the check, and the backend keeps it. The default backend does nothing.
     */
    fun reconnectNow(machineId: String, deadSessionId: Long?) = Unit

    /**
     * Closes the network sockets of [machineId] after the device lost its network. Thus no attempt waits for a TCP
     * timeout on the old network. The reconnect loop then makes no attempt until [reconnectNow], because an attempt
     * with no network fails. The default backend does nothing.
     */
    fun closeSockets(machineId: String) = Unit

    /** Reads the snapshot and the surface of [machine] again, for example after a reconnect. */
    suspend fun refreshSurface(machine: HostMachine, route: ResolvedSshRoute) = Unit
}
