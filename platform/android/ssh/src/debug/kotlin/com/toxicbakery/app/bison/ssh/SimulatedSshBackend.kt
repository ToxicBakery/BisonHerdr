package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.model.TransportMode
import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.protocol.CellData
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.ClientShellAgent
import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest
import com.toxicbakery.app.bison.protocol.ClientShellPane
import com.toxicbakery.app.bison.protocol.ClientShellSnapshot
import com.toxicbakery.app.bison.protocol.ClientShellTab
import com.toxicbakery.app.bison.protocol.ClientShellWorkspace
import com.toxicbakery.app.bison.protocol.CursorShape
import com.toxicbakery.app.bison.protocol.CursorState
import com.toxicbakery.app.bison.protocol.FrameData
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import com.toxicbakery.app.bison.protocol.MoshBootstrapConfig
import com.toxicbakery.app.bison.protocol.PaneSurfaceFrame
import com.toxicbakery.app.bison.protocol.PaneSurfacePane
import com.toxicbakery.app.bison.protocol.SemanticNotification
import com.toxicbakery.app.bison.protocol.SurfaceDeltaAssembler
import com.toxicbakery.app.bison.protocol.SurfaceRect
import com.toxicbakery.app.bison.ssh.SshTransportManager.Companion.DEFAULT_COLS
import com.toxicbakery.app.bison.ssh.SshTransportManager.Companion.DEFAULT_ROWS
import com.toxicbakery.app.bison.ssh.SshTransportManager.Companion.HARDWARE_KEY_NOT_SUPPORTED
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Debug [SshBackend] that simulates a Herdr host. It does not open a network connection.
 *
 * The debug bindings use this backend only for the machines that the fake server made. This class is in
 * the `src/debug` source set, so the release variant does not compile it and release builds do not contain it.
 *
 * The backend gives the same backbone as [RealSshBackend]: the SSH session backbone, because this version has no Mosh
 * transport (issue #22). The round trip time is [SIMULATED_RTT_MS].
 *
 * The test hooks [moshServerProbe], [sshRouteProbe], and [passwordAuthenticator] change the simulated results.
 */
class SimulatedSshBackend(
    private val sshStateManager: SshStateManager,
    private val sshDebugLog: SshDebugLog,
    private val endpointSnapshotBus: EndpointSnapshotBus,
    private val paneSurfaceBus: PaneSurfaceBus,
    private val semanticNotificationBus: SemanticNotificationBus,
    private val keystoreKeyManager: AndroidKeystoreKeyManager,
) : SshBackend {
    /** Returns true when the simulated host has `mosh-server`. With no hook, each simulated host has it. */
    internal var moshServerProbe: ((HostMachine) -> Boolean)? = null
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
                val exported = try {
                    keystoreKeyManager.getOrCreatePublicKey(
                        keyAlias = auth.keyAlias,
                        algorithm = auth.algorithm,
                        requireBiometric = auth.requireBiometric,
                    )
                } catch (error: KeyCreationException) {
                    // The device key store did not make the key. The login error gives the cause.
                    return SshAuthResult.Failed(error)
                }
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

            // SshTransportManager refuses this method before it calls a backend (issue #23).
            is SshAuthMethod.HardwareSecurityKey ->
                SshAuthResult.Failed(UnsupportedOperationException(HARDWARE_KEY_NOT_SUPPORTED))
        }
    }

    /**
     * Returns the SSH session backbone with [SIMULATED_RTT_MS], as [RealSshBackend] does. This version has no Mosh
     * transport (issue #22). [moshServerProbe] tells if the simulated host has `mosh-server`. The fallback reason
     * comes from [MoshSessionEngine.fallbackReasonFor], and the backend writes it to the debug log.
     */
    override suspend fun negotiateBackbone(
        machine: HostMachine,
        route: ResolvedSshRoute,
    ): ActiveBackbone.SshEndpoint {
        val machineId = machine.id
        sshStateManager.setConnectionProgress(machineId, ConnectionProgress.VerifyingUdpOrHello)
        val hasMoshServer = machine.transportMode != TransportMode.SshEndpointOnly &&
            (moshServerProbe?.invoke(machine) ?: true)
        val reason = MoshSessionEngine.fallbackReasonFor(machine.transportMode, hasMoshServer)
        reason?.let { sshDebugLog.record(machineId = machineId, tag = "Transport", message = it) }

        sshDebugLog.record(
            machineId = machineId,
            tag = SIMULATED_HOST_LOG_TAG,
            message = "Simulated the SSH session of ${machine.displayAddress}. The simulated host runs no Herdr command.",
        )
        return ActiveBackbone.SshEndpoint(
            rttMs = SIMULATED_RTT_MS,
            fallbackReason = reason,
        )
    }

    override suspend fun openSurface(
        machine: HostMachine,
        route: ResolvedSshRoute,
        backbone: ActiveBackbone,
        isSurfaceActive: () -> Boolean,
    ) {
        val machineId = machine.id

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

        if (paneSurfaceBus.surfaceFor(machineId) == null) {
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
                        title = "${machine.name} · Simulated SSH Session",
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
            snippet = "Connected to ${machine.displayAddress} over a simulated SSH session",
        )
        semanticNotificationBus.tryPublishNotification(
            machineId = machineId,
            notification = SemanticNotification(
                bootId = "boot-$machineId",
                sequence = 1u,
                title = "Connected to ${machine.name}",
                body = "Simulated SSH session",
                paneId = "w1:p1",
                agentName = "claude",
                agentStatus = AgentStatus.Working,
            ),
        )
    }

    /**
     * Applies a focus RPC to the simulated snapshot, and returns a simulated success. The response names the lane
     * that the target design gives the request ([designLaneOf]), so that a test can check the lane rules.
     */
    override suspend fun sendEndpointCommand(
        machine: HostMachine,
        route: ResolvedSshRoute,
        request: ClientShellEndpointRequest,
        isMoshActive: Boolean,
    ): String {
        applyFocusRpcMutation(machine.id, request)
        return buildJsonObject {
            put("id", request.id)
            putJsonObject("result") {
                put("type", "ok")
                put("lane", designLaneOf(request, isMoshActive))
            }
        }.toString()
    }

    /** The simulated host has no terminal, so the input has no effect. */
    override suspend fun sendInput(
        machine: HostMachine,
        route: ResolvedSshRoute,
        paneId: String,
        event: ClientPaneInputEvent,
    ): Boolean = true

    /** The simulated host has no viewport. */
    override fun resize(machineId: String, cols: UShort, rows: UShort) = Unit

    /** The simulated host has no session. */
    override fun disconnect(machineId: String) = Unit

    /** Writes the debug log entry that [SshTransportManager.sendEndpointCommand] writes for an RPC. */
    private fun recordApiBridgeRpc(machineId: String, request: ClientShellEndpointRequest) {
        sshDebugLog.record(
            machineId = machineId,
            tag = "RPC",
            message = HerdrRpcBuilder.toNdjsonLine(request).trim(),
        )
    }

    private fun parseParamsObject(paramsJson: String): JsonObject =
        runCatching { json.parseToJsonElement(paramsJson).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }

    private fun applyFocusRpcMutation(
        machineId: String,
        request: ClientShellEndpointRequest,
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
                focusPaneInSnapshotAndSurface(machineId, currentSnapshot, paneId)
            }

            "agent.focus" -> {
                val params = parseParamsObject(request.paramsJson)
                val requestedPaneId = params["pane_id"]?.jsonPrimitive?.content
                val requestedName = params["name"]?.jsonPrimitive?.content
                val resolvedPaneId = requestedPaneId
                    ?: currentSnapshot.agents.firstOrNull { it.name == requestedName }?.paneId
                    ?: return
                focusPaneInSnapshotAndSurface(machineId, currentSnapshot, resolvedPaneId)
            }
        }
    }

    private fun focusPaneInSnapshotAndSurface(
        machineId: String,
        currentSnapshot: ClientShellSnapshot,
        paneId: String,
    ) {
        val updatedSnapshot = currentSnapshot.copy(
            revision = currentSnapshot.revision + 1u,
            focusedPaneId = paneId,
            panes = currentSnapshot.panes.map { it.copy(focused = it.paneId == paneId) },
            agents = currentSnapshot.agents.map { it.copy(focused = it.paneId == paneId) },
        )
        endpointSnapshotBus.publish(machineId, updatedSnapshot)

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

    companion object {
        /** The round trip time of the backbone of a simulated host. */
        internal const val SIMULATED_RTT_MS: Int = 24

        /** The debug log tag of the entry that [negotiateBackbone] writes. Only this backend writes it. */
        const val SIMULATED_HOST_LOG_TAG: String = "SimulatedHost"

        /** The `herdr remote-api-bridge` lane (`herdr.sock`) of the target design. */
        internal const val API_BRIDGE_LANE: String = "herdr remote-api-bridge (herdr.sock)"

        /** The `herdr remote-client-bridge` lane (`herdr-client.sock`) of the target design. */
        internal const val CLIENT_BRIDGE_LANE: String = "herdr remote-client-bridge (herdr-client.sock)"

        /**
         * Returns the lane that the target design gives [request]. In Mosh mode (issue #22), a focus RPC goes over
         * `herdr.sock` ([MoshBootstrapConfig.isFocusRpcRequiringApiSocketInMoshMode]). A method that is not a
         * `client_shell` method also goes over `herdr.sock`. Each other request goes over `herdr-client.sock`. The
         * real backend of this version sends each request over `herdr remote-api-bridge`.
         *
         * The target design also sends each request over `herdr.sock` while the surface lease is off. The simulation
         * does not apply this rule.
         */
        internal fun designLaneOf(request: ClientShellEndpointRequest, isMoshActive: Boolean): String {
            val useApiBridge = (isMoshActive && MoshBootstrapConfig.isFocusRpcRequiringApiSocketInMoshMode(request.method)) ||
                !MoshBootstrapConfig.isClientShellSupportedMethod(request.method)
            return if (useApiBridge) API_BRIDGE_LANE else CLIENT_BRIDGE_LANE
        }

        private val BLANK_CELL = CellData()
    }
}
