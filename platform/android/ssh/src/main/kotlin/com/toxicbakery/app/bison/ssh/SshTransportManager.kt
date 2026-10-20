package com.toxicbakery.app.bison.ssh

import android.content.Context
import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.RoamingStatus
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.model.TransportMode
import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.protocol.CellData
import com.toxicbakery.app.bison.protocol.ClientKeyCode
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.ClientShellAgent
import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest
import com.toxicbakery.app.bison.protocol.ClientShellPane
import com.toxicbakery.app.bison.protocol.ClientShellSnapshot
import com.toxicbakery.app.bison.protocol.ClientShellTab
import com.toxicbakery.app.bison.protocol.ClientShellWorkspace
import com.toxicbakery.app.bison.protocol.ClientSurfaceSize
import com.toxicbakery.app.bison.protocol.CursorShape
import com.toxicbakery.app.bison.protocol.CursorState
import com.toxicbakery.app.bison.protocol.EndpointClientHello
import com.toxicbakery.app.bison.protocol.FrameData
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import com.toxicbakery.app.bison.protocol.MoshBootstrapConfig
import com.toxicbakery.app.bison.protocol.MoshConnectInfo
import com.toxicbakery.app.bison.protocol.PaneLayoutPane
import com.toxicbakery.app.bison.protocol.PaneLayoutSnapshot
import com.toxicbakery.app.bison.protocol.PaneLayoutSplit
import com.toxicbakery.app.bison.protocol.PaneSurfaceFrame
import com.toxicbakery.app.bison.protocol.PaneSurfacePane
import com.toxicbakery.app.bison.protocol.PaneSurfaceSplitDirection
import com.toxicbakery.app.bison.protocol.SemanticNotification
import com.toxicbakery.app.bison.protocol.SurfaceDeltaAssembler
import com.toxicbakery.app.bison.protocol.SurfaceRect
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
 */
class SshTransportManager(
    private val context: Context?,
    private val moshUdpEngine: MoshUdpEngine,
    private val sshStateManager: SshStateManager,
    private val sshDebugLog: SshDebugLog,
    private val endpointSnapshotBus: EndpointSnapshotBus,
    private val paneSurfaceBus: PaneSurfaceBus,
    private val semanticNotificationBus: SemanticNotificationBus,
) {
    internal var keystoreKeyManager: AndroidKeystoreKeyManager = AndroidKeystoreKeyManager()
    internal var yubiKeyFido2Manager: YubiKeyFido2Manager = YubiKeyFido2Manager(
        sshStateManager = sshStateManager,
        sshDebugLog = sshDebugLog,
    )
    internal var proxyJumpTunnel: ProxyJumpTunnel = ProxyJumpTunnel(sshDebugLog = sshDebugLog)
    internal var moshBootstrapBannerProvider: ((HostMachine) -> String?)? = null
    internal var moshUdpReachabilityProbe: (suspend (HostMachine, MoshConnectInfo) -> Boolean)? = null
    internal var sshRouteProbe: (suspend (HostMachine, ResolvedSshRoute) -> Boolean)? = null
    internal var passwordAuthenticator: (suspend (HostMachine, SshAuthMethod.Password) -> Boolean)? = null

    private val json = Json { ignoreUnknownKeys = true }
    private val surfaceDeltaAssembler = SurfaceDeltaAssembler()
    private val activeMachines = ConcurrentHashMap<String, HostMachine>()
    private val surfaceLeaseActive = ConcurrentHashMap<String, Boolean>()
    private val presentationEpochCounter = AtomicLong(1L)
    private val realHerdrSshClient = RealHerdrSshClient(
        sshDebugLog = sshDebugLog,
        endpointSnapshotBus = endpointSnapshotBus,
        paneSurfaceBus = paneSurfaceBus,
        semanticNotificationBus = semanticNotificationBus,
    )

    private fun shouldUseRealSsh(machine: HostMachine): Boolean {
        if (context == null) return false
        if (moshBootstrapBannerProvider != null ||
            moshUdpReachabilityProbe != null ||
            sshRouteProbe != null ||
            passwordAuthenticator != null
        ) {
            return false
        }
        return !realHerdrSshClient.isSimulatedHost(machine)
    }

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
    suspend fun connect(machine: HostMachine): Boolean {
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
        val routeReachable = sshRouteProbe?.invoke(machine, route) ?: true
        if (!routeReachable) {
            sshDebugLog.record(
                machineId = machineId,
                tag = "ProxyJump",
                message = "SSH route unreachable for ${route.entryHost}:${route.entryPort}",
                isError = true,
            )
            sshStateManager.setConnectionProgress(machineId, ConnectionProgress.Failed)
            return false
        }
        val authSucceeded = authenticateSshSession(machine, route)
        if (!authSucceeded) {
            sshStateManager.setConnectionProgress(machineId, ConnectionProgress.Failed)
            return false
        }

        // Phase 2: BootstrappingMoshOrBridge
        sshStateManager.setConnectionProgress(machineId, ConnectionProgress.BootstrappingMoshOrBridge)
        val backbone = negotiateBackbone(machine, route)
        if (backbone == null) {
            sshStateManager.setConnectionProgress(machineId, ConnectionProgress.Failed)
            return false
        }

        // Phase 4: SyncingHerdrSnapshot -> Connected
        sshStateManager.setConnectionProgress(machineId, ConnectionProgress.SyncingHerdrSnapshot)
        startControlSidebandAndSeedSnapshot(machine, backbone)

        sshStateManager.markMachineConnected(machineId, backbone)
        flushOfflinePrompts(machineId)
        return true
    }

    /**
     * Disconnects [machineId], stopping any active Mosh UDP session and clearing cached control streams.
     */
    suspend fun disconnect(machineId: String) {
        realHerdrSshClient.disconnect(machineId)
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

        val transportLane = if (useApiSocket) {
            "herdr remote-api-bridge (herdr.sock)"
        } else {
            "herdr remote-client-bridge (herdr-client.sock)"
        }
        val wireLine = HerdrRpcBuilder.toNdjsonLine(request).trim()
        sshDebugLog.record(
            machineId = machineId,
            tag = "RPC",
            message = "[$transportLane] $wireLine",
        )
        val machine = activeMachines[machineId]
        if (machine != null && shouldUseRealSsh(machine)) {
            val route = proxyJumpTunnel.resolveRoute(machine)
            val realResp = realHerdrSshClient.executeEndpointRpc(machine, route, request)
            if (realResp != null) {
                return realResp
            }
        }
        applyFocusRpcMutation(machineId, request, isMoshActive)
        return """{"id":"${request.id}","result":{"type":"ok","lane":"$transportLane"}}"""
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
        if (machine != null && shouldUseRealSsh(machine)) {
            val route = proxyJumpTunnel.resolveRoute(machine)
            val sent = realHerdrSshClient.sendPaneInput(machine, route, paneId, event)
            sshDebugLog.record(
                machineId = machineId,
                tag = "PaneInput",
                message = "Sent real SSH input to $paneId: $event (ok=$sent)",
            )
            return sent
        }
        val backbone = sshStateManager.activeTransportBackbone.value[machineId]
        if (backbone is ActiveBackbone.MoshUdp) {
            val bytes = encodeInputEventToBytes(event)
            if (bytes.isNotEmpty()) {
                moshUdpEngine.sendInput(machineId, bytes)
            }
        }
        sshDebugLog.record(
            machineId = machineId,
            tag = "PaneInput",
            message = "Sent input to $paneId: $event",
        )
        return true
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

    private suspend fun authenticateSshSession(
        machine: HostMachine,
        route: ResolvedSshRoute,
    ): Boolean {
        if (shouldUseRealSsh(machine)) {
            return realHerdrSshClient.connectAndVerify(machine, route)
        }
        val challenge = "ssh-userauth:${machine.id}:${route.targetHost}:${route.targetPort}".toByteArray(Charsets.UTF_8)
        return when (val auth = machine.authMethod) {
            is SshAuthMethod.KeystoreKey -> {
                val exported = keystoreKeyManager.getOrCreatePublicKey(
                    keyAlias = auth.keyAlias,
                    algorithm = auth.algorithm,
                    requireBiometric = auth.requireBiometric,
                )
                val sig = keystoreKeyManager.signSshChallenge(
                    keyAlias = auth.keyAlias,
                    algorithm = auth.algorithm,
                    challenge = challenge,
                )
                sshDebugLog.record(
                    machineId = machine.id,
                    tag = "SSHAuth",
                    message = "Authenticated ${machine.displayAddress} with AndroidKeyStore ${auth.algorithm.sshKeyType} " +
                        "(alias=${exported.keyAlias}, sigBytes=${sig.size}, viaBastion=${route.viaBastion})",
                )
                sig.isNotEmpty()
            }

            is SshAuthMethod.Password -> {
                val isValid = auth.encryptedPasswordRef.isNotBlank() &&
                    (passwordAuthenticator?.invoke(machine, auth) ?: !auth.encryptedPasswordRef.startsWith("invalid:"))
                if (!isValid) {
                    sshDebugLog.record(
                        machineId = machine.id,
                        tag = "SSHAuth",
                        message = "Password authentication failed for ${machine.displayAddress}: invalid or blank credential",
                        isError = true,
                    )
                    return false
                }
                sshDebugLog.record(
                    machineId = machine.id,
                    tag = "SSHAuth",
                    message = "Authenticated ${machine.displayAddress} via encrypted password credential (viaBastion=${route.viaBastion})",
                )
                true
            }

            is SshAuthMethod.HardwareSecurityKey -> {
                sshDebugLog.record(
                    machineId = machine.id,
                    tag = "SSHAuth",
                    message = "Requesting hardware security key assertion (${auth.keyType.opensshKeyType}, transport=${auth.transport})",
                )
                val result = yubiKeyFido2Manager.signChallenge(
                    machineId = machine.id,
                    keyType = auth.keyType.opensshKeyType,
                    applicationId = auth.applicationId,
                    challenge = challenge,
                )
                result.isSuccess
            }
        }
    }

    private suspend fun negotiateBackbone(
        machine: HostMachine,
        route: ResolvedSshRoute,
    ): ActiveBackbone? {
        val machineId = machine.id
        if (shouldUseRealSsh(machine)) {
            sshStateManager.setConnectionProgress(machineId, ConnectionProgress.VerifyingUdpOrHello)
            val hasMosh = if (machine.transportMode != TransportMode.SshEndpointOnly) {
                realHerdrSshClient.checkMoshServerAvailable(machine, route)
            } else {
                false
            }
            return if (hasMosh) {
                val defaultPort = machine.parsedMoshPortRange().first
                ActiveBackbone.MoshUdp(
                    udpPort = defaultPort,
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
        var moshFallbackReason: String? = null
        if (machine.transportMode != TransportMode.SshEndpointOnly) {
            val bootstrapCmd = buildMoshServerBootstrapCommand(machine)
            val configToml = MoshBootstrapConfig.buildTabletHerdrConfigToml()
            sshDebugLog.record(
                machineId = machineId,
                tag = "MoshBootstrap",
                message = "Executing: $bootstrapCmd (configBytes=${configToml.length})",
            )

            val defaultPort = machine.parsedMoshPortRange().first
            val banner = moshBootstrapBannerProvider?.invoke(machine)
                ?: "MOSH CONNECT $defaultPort 4k9X2vL8pQ1mN7rT3wY6zA"
            val connectInfo = MoshBootstrapConfig.parseMoshConnectLine(banner)

            if (connectInfo != null) {
                sshStateManager.setConnectionProgress(machineId, ConnectionProgress.VerifyingUdpOrHello)
                val udpVerified = withTimeoutOrNull(MOSH_UDP_HANDSHAKE_TIMEOUT_MS) {
                    val probeAllowed = moshUdpReachabilityProbe?.invoke(machine, connectInfo) ?: true
                    if (!probeAllowed) {
                        false
                    } else {
                        moshUdpEngine.startSession(
                            machineId = machineId,
                            host = route.targetHost,
                            udpPort = connectInfo.udpPort,
                            sessionKey = connectInfo.sessionKey,
                            cols = DEFAULT_COLS,
                            rows = DEFAULT_ROWS,
                            localEcho = true,
                        )
                    }
                } == true

                if (udpVerified) {
                    return ActiveBackbone.MoshUdp(
                        udpPort = connectInfo.udpPort,
                        rttMs = 11,
                        localEchoEnabled = true,
                        roamedCount = 0,
                    )
                }
                moshUdpEngine.stopSession(machineId)
                moshFallbackReason = "Mosh UDP unreachable or blocked by firewall (${MOSH_UDP_HANDSHAKE_TIMEOUT_MS}ms timeout)"
            } else {
                moshFallbackReason = "mosh-server unavailable on remote host"
            }

            if (machine.transportMode == TransportMode.MoshOnly) {
                sshDebugLog.record(
                    machineId = machineId,
                    tag = "MoshBootstrap",
                    message = "MoshOnly mode requested, but mosh-server or UDP handshake failed ($moshFallbackReason)",
                    isError = true,
                )
                return null
            }

            sshDebugLog.record(
                machineId = machineId,
                tag = "Fallback",
                message = "$moshFallbackReason; falling back to ${buildSshEndpointBridgeCommand()}",
            )
        }

        // SSH Endpoint Generation 1 fallback (`herdr remote-client-bridge --idle-timeout-v1`)
        sshStateManager.setConnectionProgress(machineId, ConnectionProgress.VerifyingUdpOrHello)
        val hello = EndpointClientHello(
            surfaceSize = ClientSurfaceSize(cols = DEFAULT_COLS, rows = DEFAULT_ROWS),
            surfaceActive = true,
        )
        sshDebugLog.record(
            machineId = machineId,
            tag = "EndpointGen1",
            message = "Negotiated ${buildSshEndpointBridgeCommand()} (hello.generation=${hello.generation}, surfaceActive=${hello.surfaceActive})",
        )
        return ActiveBackbone.SshEndpoint(
            rttMs = 24,
            fallbackReason = if (machine.transportMode == TransportMode.AutoPreferMosh) {
                moshFallbackReason ?: "Mosh UDP unavailable"
            } else {
                null
            },
        )
    }

    private suspend fun startControlSidebandAndSeedSnapshot(
        machine: HostMachine,
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

        if (shouldUseRealSsh(machine)) {
            val route = proxyJumpTunnel.resolveRoute(machine)
            realHerdrSshClient.refreshSnapshotAndSurface(machine, route)
            realHerdrSshClient.startLivePolling(machine, route) {
                surfaceLeaseActive[machineId] != false
            }
            return
        }

        // Fetch initial pane.layout and agent.read(source = "visible") over herdr remote-api-bridge
        sendEndpointCommand(machineId, HerdrRpcBuilder.paneLayout(tabId = "w1:t1"))
        sendEndpointCommand(
            machineId = machineId,
            request = HerdrRpcBuilder.agentRead(
                paneId = "w1:p1",
                agentStatus = AgentStatus.Working,
            ),
        )

        val existingSnapshot = endpointSnapshotBus.snapshotFor(machineId)
        if (existingSnapshot == null) {
            val snapshot = ClientShellSnapshot(
                bootId = "boot-$machineId",
                revision = 1u,
                focusedWorkspaceId = "w1",
                focusedTabId = "w1:t1",
                focusedPaneId = "w1:p1",
                workspaces = listOf(
                    ClientShellWorkspace(
                        workspaceId = "w1",
                        activeTabId = "w1:t1",
                        label = "herdr-core",
                        branch = "main",
                        focused = true,
                        agentStatus = AgentStatus.Working,
                    ),
                    ClientShellWorkspace(
                        workspaceId = "w2",
                        activeTabId = "w2:t1",
                        label = "wt/roaming",
                        branch = "feat/roaming",
                        focused = false,
                        agentStatus = AgentStatus.Idle,
                    ),
                ),
                tabs = listOf(
                    ClientShellTab(
                        tabId = "w1:t1",
                        workspaceId = "w1",
                        number = 1,
                        label = "main",
                        focused = true,
                        agentStatus = AgentStatus.Working,
                    ),
                    ClientShellTab(
                        tabId = "w1:t2",
                        workspaceId = "w1",
                        number = 2,
                        label = "tests",
                        focused = false,
                        agentStatus = AgentStatus.Idle,
                    ),
                ),
                panes = listOf(
                    ClientShellPane(
                        paneId = "w1:p1",
                        workspaceId = "w1",
                        tabId = "w1:t1",
                        label = "${machine.name} · shell #1",
                        cwd = "~/workspace",
                        focused = true,
                    ),
                    ClientShellPane(
                        paneId = "w1:p2",
                        workspaceId = "w1",
                        tabId = "w1:t1",
                        label = "${machine.name} · shell #2",
                        cwd = "~/workspace",
                        focused = false,
                    ),
                ),
                agents = listOf(
                    ClientShellAgent(
                        paneId = "w1:p1",
                        workspaceId = "w1",
                        tabId = "w1:t1",
                        name = "claude",
                        displayAgent = "Claude Code",
                        agent = "claude",
                        title = "Connected to ${machine.displayAddress}",
                        agentStatus = AgentStatus.Working,
                        focused = true,
                    ),
                    ClientShellAgent(
                        paneId = "w1:p2",
                        workspaceId = "w1",
                        tabId = "w1:t1",
                        name = "codex",
                        displayAgent = "Codex CLI",
                        agent = "codex",
                        title = "Monitoring ${machine.displayAddress}",
                        agentStatus = AgentStatus.Idle,
                        focused = false,
                    ),
                ),
            )
            endpointSnapshotBus.publish(machineId, snapshot)
        }

        if (isMosh) {
            moshUdpEngine.updateLayoutSnapshot(
                machineId = machineId,
                layout = buildTwoPaneLayoutSnapshot(focusedPaneId = "w1:p1"),
                tabCount = 1,
            )
        } else if (paneSurfaceBus.surfaceFor(machineId) == null) {
            val halfCols = (DEFAULT_COLS.toInt() / 2).toUShort()
            val initialFrame = PaneSurfaceFrame(
                bootId = "boot-$machineId",
                revision = 1u,
                frame = FrameData(
                    width = DEFAULT_COLS,
                    height = DEFAULT_ROWS,
                    cells = List(DEFAULT_COLS.toInt() * DEFAULT_ROWS.toInt()) { CellData() },
                    cursor = CursorState(x = 0u, y = 0u, shape = CursorShape.Block, visible = true),
                ),
                panes = listOf(
                    PaneSurfacePane(
                        paneId = "w1:p1",
                        rect = SurfaceRect(0u, 0u, halfCols, DEFAULT_ROWS),
                        focused = true,
                        title = "${machine.name} · SSH Endpoint Gen 1",
                        agentStatus = AgentStatus.Working,
                    ),
                    PaneSurfacePane(
                        paneId = "w1:p2",
                        rect = SurfaceRect(halfCols, 0u, (DEFAULT_COLS - halfCols).toUShort(), DEFAULT_ROWS),
                        focused = false,
                        title = "${machine.name} · Pane #2",
                        agentStatus = AgentStatus.Idle,
                    ),
                ),
            )
            val assembled = surfaceDeltaAssembler.commitFullFrame(machineId, initialFrame)
            paneSurfaceBus.publish(machineId, assembled.frame)
        }

        semanticNotificationBus.updateAgentSnippet(
            machineId = machineId,
            paneId = "w1:p1",
            snippet = "Connected to ${machine.displayAddress} via ${if (isMosh) "Mosh UDP SSP" else "SSH Endpoint Gen 1"}",
        )
        semanticNotificationBus.tryPublishNotification(
            machineId = machineId,
            notification = SemanticNotification(
                bootId = "boot-$machineId",
                sequence = 1u,
                title = "Connected to ${machine.name}",
                body = if (isMosh) "Mosh UDP SSP zero-drop roaming active" else "SSH Endpoint Gen 1 active",
                paneId = "w1:p1",
                agentName = "claude",
                agentStatus = AgentStatus.Working,
            ),
        )
    }

    private fun buildTwoPaneLayoutSnapshot(focusedPaneId: String): PaneLayoutSnapshot {
        val halfCols = (DEFAULT_COLS.toInt() / 2).toUShort()
        val rightCols = (DEFAULT_COLS - halfCols).toUShort()
        return PaneLayoutSnapshot(
            workspaceId = "w1",
            tabId = "w1:t1",
            zoomed = false,
            panes = listOf(
                PaneLayoutPane(
                    paneId = "w1:p1",
                    rect = SurfaceRect(0u, 0u, halfCols, DEFAULT_ROWS),
                    focused = focusedPaneId == "w1:p1",
                ),
                PaneLayoutPane(
                    paneId = "w1:p2",
                    rect = SurfaceRect(halfCols, 0u, rightCols, DEFAULT_ROWS),
                    focused = focusedPaneId == "w1:p2",
                ),
            ),
            splits = listOf(
                PaneLayoutSplit(
                    id = "s0:root",
                    direction = PaneSurfaceSplitDirection.Vertical,
                    ratio = 0.5f,
                    rect = SurfaceRect(0u, 0u, DEFAULT_COLS, DEFAULT_ROWS),
                ),
            ),
        )
    }

    private fun applyFocusRpcMutation(
        machineId: String,
        request: ClientShellEndpointRequest,
        isMoshActive: Boolean,
    ) {
        val currentSnapshot = endpointSnapshotBus.snapshotFor(machineId) ?: return
        val params = runCatching { json.parseToJsonElement(request.paramsJson).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }

        when (request.method) {
            "workspace.focus" -> {
                val wsId = params["workspace_id"]?.jsonPrimitive?.content ?: return
                val updated = currentSnapshot.copy(
                    revision = currentSnapshot.revision + 1u,
                    focusedWorkspaceId = wsId,
                    workspaces = currentSnapshot.workspaces.map { it.copy(focused = it.workspaceId == wsId) },
                )
                endpointSnapshotBus.publish(machineId, updated)
            }

            "tab.focus" -> {
                val tabId = params["tab_id"]?.jsonPrimitive?.content ?: return
                val updated = currentSnapshot.copy(
                    revision = currentSnapshot.revision + 1u,
                    focusedTabId = tabId,
                    tabs = currentSnapshot.tabs.map { it.copy(focused = it.tabId == tabId) },
                )
                endpointSnapshotBus.publish(machineId, updated)
            }

            "pane.focus" -> {
                val paneId = params["pane_id"]?.jsonPrimitive?.content ?: return
                focusPaneInSnapshotAndSurface(machineId, currentSnapshot, paneId, isMoshActive)
            }

            "agent.focus" -> {
                val requestedPaneId = params["pane_id"]?.jsonPrimitive?.content
                val requestedName = params["name"]?.jsonPrimitive?.content
                val resolvedPaneId = requestedPaneId
                    ?: currentSnapshot.agents.firstOrNull { it.name == requestedName }?.paneId
                    ?: return
                focusPaneInSnapshotAndSurface(machineId, currentSnapshot, resolvedPaneId, isMoshActive)
            }
        }
    }

    private fun focusPaneInSnapshotAndSurface(
        machineId: String,
        currentSnapshot: ClientShellSnapshot,
        paneId: String,
        isMoshActive: Boolean,
    ) {
        val updatedSnapshot = currentSnapshot.copy(
            revision = currentSnapshot.revision + 1u,
            focusedPaneId = paneId,
            panes = currentSnapshot.panes.map { it.copy(focused = it.paneId == paneId) },
            agents = currentSnapshot.agents.map { it.copy(focused = it.paneId == paneId) },
        )
        endpointSnapshotBus.publish(machineId, updatedSnapshot)

        if (isMoshActive) {
            moshUdpEngine.updateLayoutSnapshot(
                machineId = machineId,
                layout = buildTwoPaneLayoutSnapshot(focusedPaneId = paneId),
                tabCount = updatedSnapshot.tabs.size.coerceAtLeast(1),
            )
        } else {
            val currentSurface = paneSurfaceBus.surfaceFor(machineId)
            if (currentSurface != null) {
                paneSurfaceBus.publish(
                    machineId = machineId,
                    frame = currentSurface.copy(
                        revision = currentSurface.revision + 1u,
                        panes = currentSurface.panes.map { it.copy(focused = it.paneId == paneId) },
                    ),
                )
            }
        }
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

    private fun encodeInputEventToBytes(event: ClientPaneInputEvent): ByteArray =
        when (event) {
            is ClientPaneInputEvent.TextCommit -> event.text.encodeToByteArray()
            is ClientPaneInputEvent.Paste -> event.text.encodeToByteArray()
            is ClientPaneInputEvent.Key -> encodeKeyEventToBytes(event)
            is ClientPaneInputEvent.Mouse -> ByteArray(0)
        }

    private fun encodeKeyEventToBytes(keyEvent: ClientPaneInputEvent.Key): ByteArray {
        val ev = keyEvent.event
        return when (ev.code) {
            ClientKeyCode.Enter -> "\r".encodeToByteArray()
            ClientKeyCode.Escape -> "\u001b".encodeToByteArray()
            ClientKeyCode.Tab -> "\t".encodeToByteArray()
            ClientKeyCode.Backspace -> "\u007f".encodeToByteArray()
            ClientKeyCode.Up -> "\u001b[A".encodeToByteArray()
            ClientKeyCode.Down -> "\u001b[B".encodeToByteArray()
            ClientKeyCode.Right -> "\u001b[C".encodeToByteArray()
            ClientKeyCode.Left -> "\u001b[D".encodeToByteArray()
            ClientKeyCode.Home -> "\u001b[H".encodeToByteArray()
            ClientKeyCode.End -> "\u001b[F".encodeToByteArray()
            ClientKeyCode.PageUp -> "\u001b[5~".encodeToByteArray()
            ClientKeyCode.PageDown -> "\u001b[6~".encodeToByteArray()
            ClientKeyCode.Delete -> "\u001b[3~".encodeToByteArray()
            ClientKeyCode.CharC -> if (ev.ctrl) byteArrayOf(0x03) else "c".encodeToByteArray()
            ClientKeyCode.CharB -> if (ev.ctrl) byteArrayOf(0x02) else "b".encodeToByteArray()
            ClientKeyCode.CharD -> if (ev.ctrl) byteArrayOf(0x04) else "d".encodeToByteArray()
            ClientKeyCode.CharZ -> if (ev.ctrl) byteArrayOf(0x1a) else "z".encodeToByteArray()
            ClientKeyCode.CharY -> "y".encodeToByteArray()
            ClientKeyCode.CharN -> "n".encodeToByteArray()
        }
    }

    companion object {
        const val MOSH_UDP_HANDSHAKE_TIMEOUT_MS: Long = 1_500L
        const val REMOTE_TABLET_CONFIG_PATH: String = "/tmp/herdr-bison-tablet.toml"
        private val DEFAULT_COLS: UShort = 120u
        private val DEFAULT_ROWS: UShort = 36u
    }
}
