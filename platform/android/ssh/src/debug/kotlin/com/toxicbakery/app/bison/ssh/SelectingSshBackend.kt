package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * Debug [SshBackend] that sends each call to [simulated] or to [real]. The machine id selects the backend.
 *
 * [isSimulated] gets only the machine id. The host name has no effect, so a real `.ts.net` or `.local`
 * host gets [real] when [isSimulated] returns false for its id.
 *
 * [authenticate] records the selected backend for the machine. All later calls for that machine go to the
 * recorded backend, also when [isSimulated] changes its answer. [disconnect] removes the record.
 *
 * [setSessionEvents] gives the receiver to the two backends, because each backend sends the events of its own
 * machines.
 */
class SelectingSshBackend(
    private val real: SshBackend,
    private val simulated: SshBackend,
    private val isSimulated: (machineId: String) -> Boolean,
) : SshBackend {

    private val recordedBackends = ConcurrentHashMap<String, SshBackend>()

    /** Returns the backend that gets the calls for [machineId]: the recorded backend, or a new selection. */
    fun backendFor(machineId: String): SshBackend = recordedBackends[machineId] ?: select(machineId)

    private fun select(machineId: String): SshBackend = if (isSimulated(machineId)) simulated else real

    override suspend fun authenticate(machine: HostMachine, route: ResolvedSshRoute): SshAuthResult {
        val backend = select(machine.id)
        val previous = recordedBackends.put(machine.id, backend)
        if (previous != null && previous !== backend) {
            previous.disconnect(machine.id)
        }
        return backend.authenticate(machine, route)
    }

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
        (recordedBackends.remove(machineId) ?: select(machineId)).disconnect(machineId)

    override fun setSessionEvents(events: SessionEvents) {
        real.setSessionEvents(events)
        simulated.setSessionEvents(events)
    }

    override suspend fun checkLiveness(machineId: String, timeout: Duration): Boolean =
        backendFor(machineId).checkLiveness(machineId, timeout)

    override fun reconnectNow(machineId: String) =
        backendFor(machineId).reconnectNow(machineId)

    override fun closeSockets(machineId: String) =
        backendFor(machineId).closeSockets(machineId)

    override suspend fun refreshSurface(machine: HostMachine, route: ResolvedSshRoute) =
        backendFor(machine.id).refreshSurface(machine, route)
}
