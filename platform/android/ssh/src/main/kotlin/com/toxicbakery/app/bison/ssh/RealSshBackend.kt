package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.TransportMode
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest

/**
 * The [SshBackend] that connects to a real Herdr host over JSch through [RealHerdrSshClient].
 *
 * Release builds use only this backend. Debug builds use it for all machines that the fake server did not make.
 */
class RealSshBackend internal constructor(
    internal val client: RealHerdrSshClient,
    private val sshStateManager: SshStateManager,
) : SshBackend {

    constructor(
        sshStateManager: SshStateManager,
        sshDebugLog: SshDebugLog,
        endpointSnapshotBus: EndpointSnapshotBus,
        paneSurfaceBus: PaneSurfaceBus,
        semanticNotificationBus: SemanticNotificationBus,
        keystoreKeyManager: AndroidKeystoreKeyManager,
    ) : this(
        client = RealHerdrSshClient(
            sshDebugLog = sshDebugLog,
            endpointSnapshotBus = endpointSnapshotBus,
            paneSurfaceBus = paneSurfaceBus,
            semanticNotificationBus = semanticNotificationBus,
            keystoreKeyManagerProvider = { keystoreKeyManager },
        ),
        sshStateManager = sshStateManager,
    )

    override suspend fun authenticate(machine: HostMachine, route: ResolvedSshRoute): Boolean =
        client.connectAndVerify(machine, route)

    override suspend fun negotiateBackbone(machine: HostMachine, route: ResolvedSshRoute): ActiveBackbone {
        sshStateManager.setConnectionProgress(machine.id, ConnectionProgress.VerifyingUdpOrHello)
        val hasMosh = if (machine.transportMode != TransportMode.SshEndpointOnly) {
            client.checkMoshServerAvailable(machine, route)
        } else {
            false
        }
        return if (hasMosh) {
            ActiveBackbone.MoshUdp(
                udpPort = machine.parsedMoshPortRange().first,
                rttMs = 9,
                localEchoEnabled = true,
                roamedCount = 0,
            )
        } else {
            ActiveBackbone.SshEndpoint(
                rttMs = 12,
                fallbackReason = null,
            )
        }
    }

    override suspend fun openSurface(
        machine: HostMachine,
        route: ResolvedSshRoute,
        backbone: ActiveBackbone,
        isSurfaceActive: () -> Boolean,
    ) {
        client.refreshSnapshotAndSurface(machine, route)
        client.startLivePolling(machine, route, isSurfaceActive)
    }

    override suspend fun sendEndpointCommand(
        machine: HostMachine,
        route: ResolvedSshRoute,
        request: ClientShellEndpointRequest,
        isMoshActive: Boolean,
    ): String? = client.executeEndpointRpc(machine, route, request)

    override suspend fun sendInput(
        machine: HostMachine,
        route: ResolvedSshRoute,
        paneId: String,
        event: ClientPaneInputEvent,
    ): Boolean = client.sendPaneInput(machine, route, paneId, event)

    override fun resize(machineId: String, cols: UShort, rows: UShort) {
        client.resizeViewport(machineId, cols, rows)
    }

    override fun disconnect(machineId: String) {
        client.disconnect(machineId)
    }
}
