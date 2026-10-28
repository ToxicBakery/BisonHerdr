package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest

/**
 * Does the work of [SshTransportManager] that uses the connection to a machine.
 *
 * [SshTransportManager] keeps the connection state, the surface lease, and the prompt queue. It calls the
 * backend for each connection step. [RealSshBackend] is the only backend in release builds. Debug builds
 * also have a simulated backend for the machines that the fake server made.
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
}
