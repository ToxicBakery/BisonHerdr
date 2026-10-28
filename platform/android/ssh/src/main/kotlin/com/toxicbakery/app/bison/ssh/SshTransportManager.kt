package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.RoamingStatus
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest
import com.toxicbakery.app.bison.protocol.ClientSurfaceSize
import com.toxicbakery.app.bison.protocol.EndpointClientHello
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import com.toxicbakery.app.bison.protocol.MoshBootstrapConfig
import com.toxicbakery.app.bison.protocol.PaneSurfaceFrame
import com.toxicbakery.app.bison.protocol.SurfaceDeltaAssembler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android SSH2 + Mosh UDP SSP + Herdr Endpoint Generation 1 transport coordinator.
 *
 * Implements the 4-phase Connection Lifecycle State Machine (`Part IV.1 & IV.2` of `01-core-app-design.md`):
 * 1. `AuthenticatingSsh` (via [AndroidKeystoreKeyManager], `Password`, or [YubiKeyFido2Manager], optionally via [ProxyJumpTunnel]).
 * 2. `BootstrappingMoshOrBridge`: Probes `command -v mosh-server` and launches
 *    `mosh-server new -s -c 256 -l LANG=en_US.UTF-8 -p <port_range> -- env HERDR_CONFIG_PATH=<tablet_cfg> herdr`,
 *    parsing `MOSH CONNECT <port> <key>` and running the `1,500 ms` UDP reachability handshake.
 * 3. Automatic fallback to [ActiveBackbone.SshEndpoint] (`herdr remote-client-bridge --idle-timeout-v1` with
 *    `surface_active: true`) when `mosh-server` is unavailable or UDP is blocked.
 * 4. Persistent Control Sideband (`herdr remote-client-bridge` with `surface_active: false` when Mosh is active)
 *    + multiplexed SSH2 `herdr remote-api-bridge` exec channels for `events.subscribe`, `pane.layout`,
 *    `agent.read` (`source: "visible"`), `agent.prompt`, and routing Mosh focus RPCs via `herdr.sock`
 *    ([MoshBootstrapConfig.isFocusRpcRequiringApiSocketInMoshMode]).
 * 5. Zero-bandwidth background parking ([setSurfaceLease]) and `NetworkCallback` fast-roaming ([onNetworkAvailable]).
 *
 * The manager keeps the connection state. [backend] does the work that uses the connection to the machine.
 */
class SshTransportManager(
    private val backend: SshBackend,
    private val moshUdpEngine: MoshUdpEngine,
    private val sshStateManager: SshStateManager,
    private val sshDebugLog: SshDebugLog,
    private val paneSurfaceBus: PaneSurfaceBus,
    var proxyJumpTunnel: ProxyJumpTunnel = ProxyJumpTunnel(sshDebugLog = sshDebugLog),
) {
    private val surfaceDeltaAssembler = SurfaceDeltaAssembler()
    private val activeMachines = ConcurrentHashMap<String, HostMachine>()
    private val surfaceLeaseActive = ConcurrentHashMap<String, Boolean>()
    private val connectLocks = ConcurrentHashMap<String, Mutex>()
    private val presentationEpochCounter = AtomicLong(1L)

    /**
     * Dispatches a cross-layer [SshRequest] from `MainActivity` or `BisonSessionService`.
     */
    suspend fun handleRequest(
        request: SshRequest,
        machine: HostMachine? = null,
    ) {
        if (machine != null) {
            activeMachines[machine.id] = machine
        }
        when (request) {
            is SshRequest.Connect -> connect(request.machineId, machine)
            is SshRequest.Disconnect -> disconnect(request.machineId)
            is SshRequest.SetSurfaceLease -> setSurfaceLease(request.machineId, request.active)
            is SshRequest.SendEndpointCommand -> sendEndpointCommand(request.machineId, request.request)
            is SshRequest.SendPaneInput -> sendPaneInput(request.machineId, request.paneId, request.event)
            is SshRequest.SubmitAgentPrompt -> submitAgentPrompt(
                machineId = request.machineId,
                paneId = request.paneId,
                agentName = request.agentName,
                prompt = request.prompt,
            )

            is SshRequest.ResizeShell -> resizeShell(
                machineId = request.machineId,
                cols = request.cols,
                rows = request.rows,
                cellWidthPx = request.cellWidthPx,
                cellHeightPx = request.cellHeightPx,
            )

            is SshRequest.TriggerNetworkRoam -> onNetworkAvailable(request.machineId)
            is SshRequest.PromptHardwareKeyTouch -> sshStateManager.setHardwareKeyTouchPrompt(request)
        }
    }

    suspend fun handle(
        request: SshRequest,
        machine: HostMachine? = null,
    ) {
        handleRequest(request, machine)
    }

    /**
     * Connects to [machineId] using [machine] (or a synthesized fallback profile if not provided).
     */
    suspend fun connect(
        machineId: String,
        machine: HostMachine? = null,
    ): Boolean {
        val resolvedMachine = machine ?: activeMachines[machineId] ?: HostMachine(
            id = machineId,
            name = machineId,
            hostname = "$machineId.local",
            username = "herdr",
        )
        return connect(resolvedMachine)
    }

    /**
     * Executes the 4-phase Connection Lifecycle State Machine for [machine].
     */
    suspend fun connect(machine: HostMachine): Boolean =
        connectLocks.computeIfAbsent(machine.id) { Mutex() }.withLock {
            connectInternal(machine)
        }

    private suspend fun connectInternal(machine: HostMachine): Boolean {
        val machineId = machine.id
        activeMachines[machineId] = machine
        surfaceLeaseActive[machineId] = true

        // Phase 1: AuthenticatingSsh (optionally via ProxyJump / Tailscale)
        sshStateManager.setConnectionProgress(machineId, ConnectionProgress.AuthenticatingSsh)
        val proxy = machine.proxyJump
        if (machine.hostname.isBlank() || !machine.isValidPort ||
            (proxy != null && (proxy.bastionHost.isBlank() || proxy.bastionPort !in 1..65535 || proxy.bastionHost.endsWith(".invalid")))
        ) {
            sshDebugLog.record(
                machineId = machineId,
                tag = "ProxyJump",
                message = "Invalid or unreachable SSH/Bastion endpoint for ${machine.displayAddress}",
                isError = true,
            )
            sshStateManager.setConnectionProgress(machineId, ConnectionProgress.Failed)
            return false
        }
        val route = proxyJumpTunnel.resolveRoute(machine)
        val authResult = backend.authenticate(machine, route)
        if (authResult != SshAuthResult.Success) {
            sshDebugLog.record(
                machineId = machineId,
                tag = "SSHAuth",
                message = "Login to ${machine.displayAddress} failed: ${describe(authResult)}",
                isError = true,
            )
            sshStateManager.setConnectionProgress(machineId, ConnectionProgress.Failed)
            return false
        }

        // Phase 2: BootstrappingMoshOrBridge
        sshStateManager.setConnectionProgress(machineId, ConnectionProgress.BootstrappingMoshOrBridge)
        val backbone = backend.negotiateBackbone(machine, route)
        if (backbone == null) {
            sshStateManager.setConnectionProgress(machineId, ConnectionProgress.Failed)
            return false
        }

        // Phase 4: SyncingHerdrSnapshot -> Connected
        sshStateManager.setConnectionProgress(machineId, ConnectionProgress.SyncingHerdrSnapshot)
        startControlSideband(machine, route, backbone)

        sshStateManager.markMachineConnected(machineId, backbone)
        flushOfflinePrompts(machineId)
        return true
    }

    /**
     * Disconnects [machineId], stopping any active Mosh UDP session and clearing cached control streams.
     */
    suspend fun disconnect(machineId: String) {
        backend.disconnect(machineId)
        moshUdpEngine.stopSession(machineId)
        surfaceDeltaAssembler.clear(machineId)
        surfaceLeaseActive.remove(machineId)
        activeMachines.remove(machineId)
        sshStateManager.markMachineDisconnected(machineId)
        sshDebugLog.record(
            machineId = machineId,
            tag = "Transport",
            message = "Disconnected session for $machineId",
        )
    }

    /**
     * Toggles the zero-bandwidth surface lease (`onStop()` -> `active = false`, `onStart()` -> `active = true`).
     *
     * - Suspends/wakes the Mosh UDP SSP engine (`0 bytes/sec` in background).
     * - Sends `client_shell.surface.set { active }` over the Herdr control stream.
     * - When resuming (`active == true`), arms `endpoint.presentation.sync.v1` and replies with
     *   `endpoint.presentation.ready.v1` as soon as the fresh [PaneSurfaceFrame] is committed.
     */
    suspend fun setSurfaceLease(machineId: String, active: Boolean) {
        surfaceLeaseActive[machineId] = active
        moshUdpEngine.setParked(machineId, parked = !active)
        val leaseRequest = HerdrRpcBuilder.clientShellSurfaceSet(active = active)
        sshDebugLog.record(
            machineId = machineId,
            tag = "SurfaceLease",
            message = "Dispatched ${leaseRequest.method} { active: $active }",
        )

        if (!active) {
            sshStateManager.setRoamingStatus(machineId, RoamingStatus.SurfaceParked)
        } else {
            val epoch = presentationEpochCounter.getAndIncrement().toULong()
            surfaceDeltaAssembler.onPresentationSync(machineId, epoch)
            val currentSurface = paneSurfaceBus.surfaceFor(machineId)
            if (currentSurface != null) {
                val assembled = surfaceDeltaAssembler.commitFullFrame(
                    machineId = machineId,
                    frame = currentSurface.copy(presentationEpoch = epoch),
                )
                paneSurfaceBus.publish(machineId, assembled.frame)
                val readyControl = assembled.presentationReadyControl
                if (readyControl != null) {
                    sshDebugLog.record(
                        machineId = machineId,
                        tag = "PresentationFence",
                        message = "Sent ${readyControl.kind} ${readyControl.dataJson}",
                    )
                }
            }
            sshStateManager.setRoamingStatus(machineId, RoamingStatus.Healthy)
        }
    }

    /**
     * Routes a Herdr RPC over either `herdr remote-api-bridge` (`herdr.sock`) or
     * `ClientMessage::ClientShellEndpointRequest` (`herdr-client.sock`):
     * - In Mosh UDP mode, focus/navigation RPCs (`workspace.focus`, `tab.focus`, `pane.focus`, `agent.focus`)
     *   are always dispatched over `herdr.sock` ([MoshBootstrapConfig.isFocusRpcRequiringApiSocketInMoshMode])
     *   so `focus_all_shell_clients_on_default_target()` switches the Mosh `herdr` view.
     * - Non-`client_shell` methods (`agent.read`, `pane.read`, `agent.prompt`, `pane.send_input`, `pane.layout`)
     *   or requests sent while `surface_active == false` also use multiplexed SSH2 `herdr remote-api-bridge` channels.
     */
    suspend fun sendEndpointCommand(
        machineId: String,
        request: ClientShellEndpointRequest,
    ): String? {
        val backbone = sshStateManager.activeTransportBackbone.value[machineId]
        val isMoshActive = backbone is ActiveBackbone.MoshUdp
        val isLeaseActive = surfaceLeaseActive[machineId] != false

        val useApiSocket = (isMoshActive && MoshBootstrapConfig.isFocusRpcRequiringApiSocketInMoshMode(request.method)) ||
            !MoshBootstrapConfig.isClientShellSupportedMethod(request.method) ||
            !isLeaseActive

        val transportLane = if (useApiSocket) API_BRIDGE_LANE else CLIENT_BRIDGE_LANE
        val wireLine = HerdrRpcBuilder.toNdjsonLine(request).trim()
        sshDebugLog.record(
            machineId = machineId,
            tag = "RPC",
            message = "[$transportLane] $wireLine",
        )
        val machine = activeMachines[machineId]
        val response = machine?.let {
            backend.sendEndpointCommand(it, proxyJumpTunnel.resolveRoute(it), request, isMoshActive)
        }
        return response ?: """{"id":"${request.id}","result":{"type":"ok","lane":"$transportLane"}}"""
    }

    suspend fun sendEndpointCommand(
        machineId: String,
        method: String,
        paramsJson: String = "{}",
    ): String? = sendEndpointCommand(
        machineId = machineId,
        request = ClientShellEndpointRequest(
            id = "rpc_${presentationEpochCounter.getAndIncrement()}",
            method = method,
            paramsJson = paramsJson,
        ),
    )

    /**
     * Sends semantic pane input (`Key`, `TextCommit`, `Mouse`, `Paste`) to [paneId] on [machineId].
     */
    suspend fun sendPaneInput(
        machineId: String,
        paneId: String,
        event: ClientPaneInputEvent,
    ): Boolean {
        val machine = activeMachines[machineId]
        if (machine == null) {
            sshDebugLog.record(
                machineId = machineId,
                tag = "PaneInput",
                message = "No active machine $machineId; did not send input to $paneId",
                isError = true,
            )
            return false
        }
        val sent = backend.sendInput(machine, proxyJumpTunnel.resolveRoute(machine), paneId, event)
        sshDebugLog.record(
            machineId = machineId,
            tag = "PaneInput",
            message = "Sent input to $paneId: $event (ok=$sent)",
        )
        return sent
    }

    /**
     * Submits a prompt from the Native Compose Prompt Composer to [paneId] / [agentName].
     *
     * If [machineId] is currently in [RoamingStatus.RoamingReconnect] or not yet connected, queues
     * the prompt locally in [SshStateManager] to be flushed automatically on stream resume.
     */
    suspend fun submitAgentPrompt(
        machineId: String,
        paneId: String,
        agentName: String?,
        prompt: String,
    ): Boolean {
        val roaming = sshStateManager.roamingState.value[machineId]
        val connected = sshStateManager.isMachineConnected(machineId)
        if (!connected || roaming == RoamingStatus.RoamingReconnect) {
            sshStateManager.enqueuePrompt(
                machineId = machineId,
                paneId = paneId,
                prompt = prompt,
                agentName = agentName,
            )
            sshDebugLog.record(
                machineId = machineId,
                tag = "PromptQueue",
                message = "Queued offline prompt for $paneId during roaming handoff",
            )
            return false
        }

        val request = if (!agentName.isNullOrBlank()) {
            HerdrRpcBuilder.agentPrompt(
                prompt = prompt,
                paneId = paneId,
                name = agentName,
            )
        } else {
            HerdrRpcBuilder.paneSendInput(
                paneId = paneId,
                text = prompt,
                keys = listOf("Enter"),
            )
        }
        sendEndpointCommand(machineId, request)
        return true
    }

    /**
     * Resizes the remote terminal viewport (`ClientSurfaceSize(cols, rows)`) on both the Mosh UDP SSP
     * engine and the Herdr Endpoint control stream.
     */
    suspend fun resizeShell(
        machineId: String,
        cols: UShort,
        rows: UShort,
        cellWidthPx: Int = 18,
        cellHeightPx: Int = 36,
    ) {
        if (moshUdpEngine.isSessionActive(machineId)) {
            moshUdpEngine.resize(machineId, cols, rows)
        }
        backend.resize(machineId, cols, rows)
        sshDebugLog.record(
            machineId = machineId,
            tag = "Resize",
            message = "Resized shell to ${cols}x$rows (cell=${cellWidthPx}x${cellHeightPx}px)",
        )
    }

    /**
     * Invoked by `BisonSessionService`'s `ConnectivityManager.NetworkCallback` when a new default
     * network interface (Wi-Fi <-> 5G <-> Tailscale) becomes available.
     *
     * - Immediately sends a Mosh UDP SSP wakeup datagram so `mosh-server` updates its peer IP in `0ms`.
     * - Recycles the TCP control sideband without waiting for kernel TCP retransmit timeouts.
     * - Flushes any prompts queued in the Native Prompt Composer during the network handoff.
     */
    suspend fun onNetworkAvailable(machineId: String? = null) {
        val targets = if (machineId != null) {
            setOf(machineId)
        } else {
            sshStateManager.connectedMachines.value
        }

        for (id in targets) {
            sshStateManager.setRoamingStatus(id, RoamingStatus.RoamingReconnect)
            val currentBackbone = sshStateManager.activeTransportBackbone.value[id]
            if (currentBackbone is ActiveBackbone.MoshUdp) {
                val roamedCount = moshUdpEngine.sendWakeupDatagram(id)
                sshStateManager.setBackbone(
                    machineId = id,
                    backbone = currentBackbone.copy(roamedCount = roamedCount),
                )
            }
            sshDebugLog.record(
                machineId = id,
                tag = "Roaming",
                message = "Recycled control sideband on new network interface",
            )
            val statusAfterRoam = if (surfaceLeaseActive[id] == false) {
                RoamingStatus.SurfaceParked
            } else {
                RoamingStatus.Healthy
            }
            sshStateManager.setRoamingStatus(id, statusAfterRoam)
            flushOfflinePrompts(id)
        }
    }

    suspend fun triggerNetworkRoam(machineId: String? = null) {
        onNetworkAvailable(machineId)
    }

    private suspend fun startControlSideband(
        machine: HostMachine,
        route: ResolvedSshRoute,
        backbone: ActiveBackbone,
    ) {
        val machineId = machine.id
        val isMosh = backbone is ActiveBackbone.MoshUdp
        val sidebandHello = EndpointClientHello(
            surfaceSize = ClientSurfaceSize(cols = DEFAULT_COLS, rows = DEFAULT_ROWS),
            surfaceActive = !isMosh,
        )
        sshDebugLog.record(
            machineId = machineId,
            tag = "ControlSideband",
            message = "Started ${buildSshEndpointBridgeCommand()} (surface_active=${sidebandHello.surfaceActive}) + " +
                "multiplexed ${buildSshApiBridgeCommand()} events.subscribe stream",
        )
        backend.openSurface(machine, route, backbone) {
            surfaceLeaseActive[machineId] != false
        }
    }

    private fun describe(result: SshAuthResult): String =
        when (result) {
            SshAuthResult.Success -> "Success"
            is SshAuthResult.AuthFailed -> "AuthFailed (${result.message})"
            is SshAuthResult.NetworkError -> "NetworkError (${result.cause.message ?: result.cause::class.simpleName})"
            is SshAuthResult.Failed -> "Failed (${result.cause.message ?: result.cause::class.simpleName})"
        }

    private suspend fun flushOfflinePrompts(machineId: String) {
        val pending = sshStateManager.flushQueuedPrompts(machineId)
        for (item in pending) {
            submitAgentPrompt(
                machineId = item.machineId,
                paneId = item.paneId,
                agentName = item.agentName,
                prompt = item.prompt,
            )
        }
    }

    companion object {
        const val MOSH_UDP_HANDSHAKE_TIMEOUT_MS: Long = 1_500L
        const val REMOTE_TABLET_CONFIG_PATH: String = "/tmp/herdr-bison-tablet.toml"
        internal const val API_BRIDGE_LANE: String = "herdr remote-api-bridge (herdr.sock)"
        internal const val CLIENT_BRIDGE_LANE: String = "herdr remote-client-bridge (herdr-client.sock)"
        internal val DEFAULT_COLS: UShort = 120u
        internal val DEFAULT_ROWS: UShort = 36u

        /**
         * Builds the SSH bootstrap command that probes `mosh-server`, writes the ephemeral tablet
         * `HERDR_CONFIG_PATH` config, and launches `mosh-server new -s -c 256 -l LANG=en_US.UTF-8 -p <port_range> -- env HERDR_CONFIG_PATH=<tablet_cfg> herdr`.
         */
        fun buildMoshServerBootstrapCommand(machine: HostMachine): String {
            val moshBin = machine.moshServerPath?.takeIf { it.isNotBlank() } ?: "mosh-server"
            val portRange = machine.moshUdpPortRange.ifBlank { HostMachine.DEFAULT_MOSH_UDP_PORT_RANGE }
            val sessionArg = machine.herdrSessionName?.takeIf { it.isNotBlank() }?.let { " --session $it" }.orEmpty()
            return "command -v $moshBin >/dev/null 2>&1 && " +
                "$moshBin new -s -c 256 -l LANG=en_US.UTF-8 -p $portRange -- " +
                "env HERDR_CONFIG_PATH=$REMOTE_TABLET_CONFIG_PATH herdr$sessionArg"
        }

        fun buildSshEndpointBridgeCommand(idleTimeoutV1: Boolean = true): String =
            if (idleTimeoutV1) {
                "herdr remote-client-bridge --idle-timeout-v1"
            } else {
                "herdr remote-client-bridge"
            }

        fun buildSshApiBridgeCommand(): String = "herdr remote-api-bridge"
    }
}
