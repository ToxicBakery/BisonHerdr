package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An [SshBackend] that records each call and opens no connection. [authenticate] returns [authResult].
 */
internal class RecordingSshBackend(
    private val authResult: SshAuthResult = SshAuthResult.Success,
) : SshBackend {

    /** One entry for each call: the method name and the machine id, for example `resize workbox`. */
    val calls: MutableList<String> = CopyOnWriteArrayList()

    override suspend fun authenticate(machine: HostMachine, route: ResolvedSshRoute): SshAuthResult {
        calls += "authenticate ${machine.id}"
        return authResult
    }

    override suspend fun negotiateBackbone(machine: HostMachine, route: ResolvedSshRoute): ActiveBackbone {
        calls += "negotiateBackbone ${machine.id}"
        return ActiveBackbone.SshEndpoint(rttMs = 1)
    }

    override suspend fun openSurface(
        machine: HostMachine,
        route: ResolvedSshRoute,
        backbone: ActiveBackbone,
        isSurfaceActive: () -> Boolean,
    ) {
        calls += "openSurface ${machine.id}"
    }

    override suspend fun sendEndpointCommand(
        machine: HostMachine,
        route: ResolvedSshRoute,
        request: ClientShellEndpointRequest,
        isMoshActive: Boolean,
    ): String? {
        calls += "sendEndpointCommand ${machine.id}"
        return null
    }

    override suspend fun sendInput(
        machine: HostMachine,
        route: ResolvedSshRoute,
        paneId: String,
        event: ClientPaneInputEvent,
    ): Boolean {
        calls += "sendInput ${machine.id}"
        return true
    }

    override fun resize(machineId: String, cols: UShort, rows: UShort) {
        calls += "resize $machineId"
    }

    override fun disconnect(machineId: String) {
        calls += "disconnect $machineId"
    }
}
