package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.HostMachine
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
import com.toxicbakery.app.bison.ssh.SshTransportManager.Companion.DEFAULT_COLS
import com.toxicbakery.app.bison.ssh.SshTransportManager.Companion.DEFAULT_ROWS
import com.toxicbakery.app.bison.ssh.SshTransportManager.Companion.MOSH_UDP_HANDSHAKE_TIMEOUT_MS
import com.toxicbakery.app.bison.ssh.SshTransportManager.Companion.buildMoshServerBootstrapCommand
import com.toxicbakery.app.bison.ssh.SshTransportManager.Companion.buildSshEndpointBridgeCommand
import java.io.IOException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Debug [SshBackend] that simulates a Herdr host. It does not open a network connection.
 *
 * The debug bindings use this backend only for the machines that the fake server made. This class is in
 * the `src/debug` source set, so the release variant does not compile it and release builds do not contain it.
 *
 * The test hooks [moshBootstrapBannerProvider], [moshUdpReachabilityProbe], [sshRouteProbe], and
 * [passwordAuthenticator] change the simulated results.
 */
class SimulatedSshBackend(
    private val moshUdpEngine: MoshUdpEngine,
    private val sshStateManager: SshStateManager,
    private val sshDebugLog: SshDebugLog,
    private val endpointSnapshotBus: EndpointSnapshotBus,
    private val paneSurfaceBus: PaneSurfaceBus,
    private val semanticNotificationBus: SemanticNotificationBus,
    private val keystoreKeyManager: AndroidKeystoreKeyManager,
    private val yubiKeyFido2Manager: YubiKeyFido2Manager,
) : SshBackend {
    internal var moshBootstrapBannerProvider: ((HostMachine) -> String?)? = null
    internal var moshUdpReachabilityProbe: (suspend (HostMachine, MoshConnectInfo) -> Boolean)? = null
    internal var sshRouteProbe: (suspend (HostMachine, ResolvedSshRoute) -> Boolean)? = null
    internal var passwordAuthenticator: (suspend (HostMachine, SshAuthMethod.Password) -> Boolean)? = null

    private val json = Json { ignoreUnknownKeys = true }
    private val surfaceDeltaAssembler = SurfaceDeltaAssembler()

    override suspend fun authenticate(
        machine: HostMachine,
        route: ResolvedSshRoute,
    ): SshAuthResult {
        val routeReachable = sshRouteProbe?.invoke(machine, route) ?: true
        if (!routeReachable) {
            val message = "SSH route unreachable for ${route.entryHost}:${route.entryPort}"
            sshDebugLog.record(
                machineId = machine.id,
                tag = "ProxyJump",
                message = message,
                isError = true,
            )
            return SshAuthResult.NetworkError(IOException(message))
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
                if (sig.isNotEmpty()) {
                    SshAuthResult.Success
                } else {
                    SshAuthResult.Failed(IllegalStateException("Empty signature from ${exported.keyAlias}"))
                }
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
                    return SshAuthResult.AuthFailed("Invalid or blank credential")
                }
                sshDebugLog.record(
                    machineId = machine.id,
                    tag = "SSHAuth",
                    message = "Authenticated ${machine.displayAddress} via encrypted password credential (viaBastion=${route.viaBastion})",
                )
                SshAuthResult.Success
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
                result.fold(
                    onSuccess = { SshAuthResult.Success },
                    onFailure = { SshAuthResult.Failed(it) },
                )
            }
        }
    }

    override suspend fun negotiateBackbone(
        machine: HostMachine,
        route: ResolvedSshRoute,
    ): ActiveBackbone? {
        val machineId = machine.id
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

    override suspend fun openSurface(
        machine: HostMachine,
        route: ResolvedSshRoute,
        backbone: ActiveBackbone,
        isSurfaceActive: () -> Boolean,
    ) {
        val machineId = machine.id
        val isMosh = backbone is ActiveBackbone.MoshUdp

        // Fetch initial pane.layout and agent.read(source = "visible") over herdr remote-api-bridge
        recordApiBridgeRpc(machineId, HerdrRpcBuilder.paneLayout(tabId = "w1:t1"))
        recordApiBridgeRpc(
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
                    cells = List(DEFAULT_COLS.toInt() * DEFAULT_ROWS.toInt()) { BLANK_CELL },
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

    /**
     * Applies a focus RPC to the simulated snapshot and surface. Returns null, because no host answers.
     */
    override suspend fun sendEndpointCommand(
        machine: HostMachine,
        route: ResolvedSshRoute,
        request: ClientShellEndpointRequest,
        isMoshActive: Boolean,
    ): String? {
        applyFocusRpcMutation(machine.id, request, isMoshActive)
        return null
    }

    override suspend fun sendInput(
        machine: HostMachine,
        route: ResolvedSshRoute,
        paneId: String,
        event: ClientPaneInputEvent,
    ): Boolean {
        val backbone = sshStateManager.activeTransportBackbone.value[machine.id]
        if (backbone is ActiveBackbone.MoshUdp) {
            val bytes = encodeInputEventToBytes(event)
            if (bytes.isNotEmpty()) {
                moshUdpEngine.sendInput(machine.id, bytes)
            }
        }
        return true
    }

    /** The simulated host has no viewport. [SshTransportManager] resizes the Mosh engine. */
    override fun resize(machineId: String, cols: UShort, rows: UShort) = Unit

    /** The simulated host has no session. [SshTransportManager] stops the Mosh engine. */
    override fun disconnect(machineId: String) = Unit

    /**
     * Writes the debug log entry that [SshTransportManager.sendEndpointCommand] writes for an RPC on the
     * `herdr remote-api-bridge` lane.
     */
    private fun recordApiBridgeRpc(machineId: String, request: ClientShellEndpointRequest) {
        sshDebugLog.record(
            machineId = machineId,
            tag = "RPC",
            message = "[${SshTransportManager.API_BRIDGE_LANE}] ${HerdrRpcBuilder.toNdjsonLine(request).trim()}",
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

    private fun parseParamsObject(paramsJson: String): JsonObject =
        runCatching { json.parseToJsonElement(paramsJson).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }

    private fun applyFocusRpcMutation(
        machineId: String,
        request: ClientShellEndpointRequest,
        isMoshActive: Boolean,
    ) {
        val currentSnapshot = endpointSnapshotBus.snapshotFor(machineId) ?: return

        when (request.method) {
            "workspace.focus" -> {
                val params = parseParamsObject(request.paramsJson)
                val wsId = params["workspace_id"]?.jsonPrimitive?.content ?: return
                val updated = currentSnapshot.copy(
                    revision = currentSnapshot.revision + 1u,
                    focusedWorkspaceId = wsId,
                    workspaces = currentSnapshot.workspaces.map { it.copy(focused = it.workspaceId == wsId) },
                )
                endpointSnapshotBus.publish(machineId, updated)
            }

            "tab.focus" -> {
                val params = parseParamsObject(request.paramsJson)
                val tabId = params["tab_id"]?.jsonPrimitive?.content ?: return
                val updated = currentSnapshot.copy(
                    revision = currentSnapshot.revision + 1u,
                    focusedTabId = tabId,
                    tabs = currentSnapshot.tabs.map { it.copy(focused = it.tabId == tabId) },
                )
                endpointSnapshotBus.publish(machineId, updated)
            }

            "pane.focus" -> {
                val params = parseParamsObject(request.paramsJson)
                val paneId = params["pane_id"]?.jsonPrimitive?.content ?: return
                focusPaneInSnapshotAndSurface(machineId, currentSnapshot, paneId, isMoshActive)
            }

            "agent.focus" -> {
                val params = parseParamsObject(request.paramsJson)
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

    private fun encodeInputEventToBytes(event: ClientPaneInputEvent): ByteArray =
        when (event) {
            is ClientPaneInputEvent.TextCommit -> event.text.encodeToByteArray()
            is ClientPaneInputEvent.Paste -> event.text.encodeToByteArray()
            is ClientPaneInputEvent.Key -> encodeKeyEventToBytes(event)
            is ClientPaneInputEvent.Mouse -> EMPTY_BYTES
        }

    private fun encodeKeyEventToBytes(keyEvent: ClientPaneInputEvent.Key): ByteArray {
        val ev = keyEvent.event
        return when (ev.code) {
            ClientKeyCode.Enter -> ENTER_BYTES
            ClientKeyCode.Escape -> ESCAPE_BYTES
            ClientKeyCode.Tab -> TAB_BYTES
            ClientKeyCode.Backspace -> BACKSPACE_BYTES
            ClientKeyCode.Up -> UP_BYTES
            ClientKeyCode.Down -> DOWN_BYTES
            ClientKeyCode.Right -> RIGHT_BYTES
            ClientKeyCode.Left -> LEFT_BYTES
            ClientKeyCode.Home -> HOME_BYTES
            ClientKeyCode.End -> END_BYTES
            ClientKeyCode.PageUp -> PAGE_UP_BYTES
            ClientKeyCode.PageDown -> PAGE_DOWN_BYTES
            ClientKeyCode.Delete -> DELETE_BYTES
            ClientKeyCode.CharC -> if (ev.ctrl) CTRL_C_BYTES else CHAR_C_BYTES
            ClientKeyCode.CharB -> if (ev.ctrl) CTRL_B_BYTES else CHAR_B_BYTES
            ClientKeyCode.CharD -> if (ev.ctrl) CTRL_D_BYTES else CHAR_D_BYTES
            ClientKeyCode.CharZ -> if (ev.ctrl) CTRL_Z_BYTES else CHAR_Z_BYTES
            ClientKeyCode.CharY -> CHAR_Y_BYTES
            ClientKeyCode.CharN -> CHAR_N_BYTES
        }
    }

    private companion object {
        val BLANK_CELL = CellData()

        val EMPTY_BYTES = ByteArray(0)
        val ENTER_BYTES = "\r".encodeToByteArray()
        val ESCAPE_BYTES = "\u001b".encodeToByteArray()
        val TAB_BYTES = "\t".encodeToByteArray()
        val BACKSPACE_BYTES = "\u007f".encodeToByteArray()
        val UP_BYTES = "\u001b[A".encodeToByteArray()
        val DOWN_BYTES = "\u001b[B".encodeToByteArray()
        val RIGHT_BYTES = "\u001b[C".encodeToByteArray()
        val LEFT_BYTES = "\u001b[D".encodeToByteArray()
        val HOME_BYTES = "\u001b[H".encodeToByteArray()
        val END_BYTES = "\u001b[F".encodeToByteArray()
        val PAGE_UP_BYTES = "\u001b[5~".encodeToByteArray()
        val PAGE_DOWN_BYTES = "\u001b[6~".encodeToByteArray()
        val DELETE_BYTES = "\u001b[3~".encodeToByteArray()
        val CTRL_C_BYTES = byteArrayOf(0x03)
        val CTRL_B_BYTES = byteArrayOf(0x02)
        val CTRL_D_BYTES = byteArrayOf(0x04)
        val CTRL_Z_BYTES = byteArrayOf(0x1a)
        val CHAR_C_BYTES = "c".encodeToByteArray()
        val CHAR_B_BYTES = "b".encodeToByteArray()
        val CHAR_D_BYTES = "d".encodeToByteArray()
        val CHAR_Z_BYTES = "z".encodeToByteArray()
        val CHAR_Y_BYTES = "y".encodeToByteArray()
        val CHAR_N_BYTES = "n".encodeToByteArray()
    }
}
