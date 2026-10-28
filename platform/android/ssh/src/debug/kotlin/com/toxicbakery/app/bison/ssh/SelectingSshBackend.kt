package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest

/**
 * Debug [SshBackend] that sends each call to [simulated] or to [real]. The machine id selects the backend.
 *
 * [isSimulated] gets only the machine id. The host name has no effect, so a real `.ts.net` or `.local`
 * host gets [real] when [isSimulated] returns false for its id.
 */
class SelectingSshBackend(
    private val real: SshBackend,
    private val simulated: SshBackend,
    private val isSimulated: (machineId: String) -> Boolean,
) : SshBackend {

    /** Returns the backend that gets the calls for [machineId]. */
    fun backendFor(machineId: String): SshBackend =
        if (isSimulated(machineId)) simulated else real

    override suspend fun authenticate(machine: HostMachine, route: ResolvedSshRoute): SshAuthResult =
        backendFor(machine.id).authenticate(machine, route)

    override suspend fun negotiateBackbone(machine: HostMachine, route: ResolvedSshRoute): ActiveBackbone? =
        backendFor(machine.id).negotiateBackbone(machine, route)

    override suspend fun openSurface(
        machine: HostMachine,
        route: ResolvedSshRoute,
        backbone: ActiveBackbone,
        isSurfaceActive: () -> Boolean,
    ) = backendFor(machine.id).openSurface(machine, route, backbone, isSurfaceActive)

    override suspend fun sendEndpointCommand(
        machine: HostMachine,
        route: ResolvedSshRoute,
        request: ClientShellEndpointRequest,
        isMoshActive: Boolean,
    ): String? = backendFor(machine.id).sendEndpointCommand(machine, route, request, isMoshActive)

    override suspend fun sendInput(
        machine: HostMachine,
        route: ResolvedSshRoute,
        paneId: String,
        event: ClientPaneInputEvent,
    ): Boolean = backendFor(machine.id).sendInput(machine, route, paneId, event)

    override fun resize(machineId: String, cols: UShort, rows: UShort) =
        backendFor(machineId).resize(machineId, cols, rows)

    override fun disconnect(machineId: String) =
        backendFor(machineId).disconnect(machineId)
}
