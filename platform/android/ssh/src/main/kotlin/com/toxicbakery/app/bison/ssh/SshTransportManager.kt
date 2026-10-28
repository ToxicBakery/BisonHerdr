package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.RoamingStatus
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest
import com.toxicbakery.app.bison.protocol.ClientSurfaceSize
import com.toxicbakery.app.bison.protocol.EndpointClientHello
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import com.toxicbakery.app.bison.protocol.MoshBootstrapConfig
import com.toxicbakery.app.bison.protocol.PaneSurfaceFrame
import com.toxicbakery.app.bison.protocol.SurfaceDeltaAssembler
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Android SSH2 + Mosh UDP SSP + Herdr Endpoint Generation 1 transport coordinator.
 *
 * Implements the 4-phase Connection Lifecycle State Machine (`Part IV.1 & IV.2` of `01-core-app-design.md`):
 * 1. `AuthenticatingSsh` (via [AndroidKeystoreKeyManager] or `Password`, optionally via [ProxyJumpTunnel]).
 *    A machine with an [SshAuthMethod] that this version does not support fails before this phase (issue #23).
 * 2. `BootstrappingMoshOrBridge`: Probes `command -v mosh-server` and launches
 *    `mosh-server new -s -c 256 -l LANG=en_US.UTF-8 -p <port_range> -- env HERDR_CONFIG_PATH=<tablet_cfg> herdr`,
 *    parsing `MOSH CONNECT <port> <key>` and running the `1,500 ms` UDP reachability handshake.
 * 3. Automatic fallback to [ActiveBackbone.SshEndpoint] (`herdr remote-client-bridge --idle-timeout-v1` with
 *    `surface_active: true`) when `mosh-server` is unavailable or UDP is blocked.
 * 4. Persistent Control Sideband (`herdr remote-client-bridge` with `surface_active: false` when Mosh is active)
 *    + multiplexed SSH2 `herdr remote-api-bridge` exec channels for `events.subscribe`, `pane.layout`,
 *    `agent.read` (`source: "visible"`), `agent.prompt`, and routing Mosh focus RPCs via `herdr.sock`
 *    ([MoshBootstrapConfig.isFocusRpcRequiringApiSocketInMoshMode]).
 * 5. Zero-bandwidth background parking ([setSurfaceLease]) and `NetworkCallback` fast-roaming ([onNetworkAvailable]
 *    and [onNetworkLost]).
 *
 * The manager keeps the connection state. [backend] does the work that uses the connection to the machine.
 *
 * After a connect, the backend reconnects on its own when the session closes, and sends the events of its loop to
 * the manager ([SessionEvents]). The manager shows [RoamingStatus.RoamingReconnect] during the loop. After a
 * reconnect, it sets the surface lease again, reads the surface again, and sends the queued prompts. When the loop
 * stops for the user, the manager puts the cause in [SshStateManager.needsUser] and marks the machine disconnected.
 *
 * [timeSource] measures the interval between two network checks of a machine. A test gives a source with virtual
 * time.
 */
class SshTransportManager(
    private val backend: SshBackend,
    private val moshUdpEngine: MoshUdpEngine,
    private val sshStateManager: SshStateManager,
    private val sshDebugLog: SshDebugLog,
    private val paneSurfaceBus: PaneSurfaceBus,
    var proxyJumpTunnel: ProxyJumpTunnel = ProxyJumpTunnel(sshDebugLog = sshDebugLog),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val surfaceDeltaAssembler = SurfaceDeltaAssembler()
    private val activeMachines = ConcurrentHashMap<String, HostMachine>()
    private val surfaceLeaseActive = ConcurrentHashMap<String, Boolean>()
    private val connectLocks = ConcurrentHashMap<String, Mutex>()
    private val presentationEpochCounter = AtomicLong(1L)

    /** The time of the last network check of each machine. See [onNetworkAvailable]. */
    private val lastNetworkChecks = ConcurrentHashMap<String, TimeMark>()

    /** Receives the events of the reconnect loop of [backend]. */
    private val reconnectEvents = object : SessionEvents {
        override fun onReconnecting(machineId: String, cause: Throwable?) {
            if (!sshStateManager.isMachineConnected(machineId)) return
            sshStateManager.setRoamingStatus(machineId, RoamingStatus.RoamingReconnect)
            val reason = cause?.let { ": ${detailOf(it)}" }.orEmpty()
            sshDebugLog.record(
                machineId = machineId,
                tag = RECONNECT_LOG_TAG,
                message = "The session closed$reason. The app connects again.",
                isError = true,
            )
        }

        override fun onReconnectFailed(
            machineId: String,
            failures: Int,
            result: SshAuthResult.NetworkError,
            nextDelay: Duration,
        ) {
            sshDebugLog.record(
                machineId = machineId,
                tag = RECONNECT_LOG_TAG,
                message = "Attempt $failures failed: ${describe(result)}. The next attempt starts in " +
                    "${nextDelay.inWholeMilliseconds} ms.",
                isError = true,
            )
        }

        override suspend fun onReconnected(machineId: String) {
            sshDebugLog.record(machineId = machineId, tag = RECONNECT_LOG_TAG, message = "The session is open again")
            restoreAfterReconnect(machineId)
        }

        override suspend fun onNeedsUser(machineId: String, result: SshAuthResult) {
            sshDebugLog.record(
                machineId = machineId,
                tag = RECONNECT_LOG_TAG,
                message = "The reconnect loop stopped: ${describe(result)}. The machine waits for the user.",
                isError = true,
            )
            stopForUser(machineId, causeOf(result), hostKeyPromptFor(machineId, result))
        }
    }

    init {
        backend.setSessionEvents(reconnectEvents)
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
     * Connects to [machineId] using [machine], or the last profile of [machineId] when [machine] is null.
     * Returns false and writes a debug log entry when no profile is available.
     */
    suspend fun connect(
        machineId: String,
        machine: HostMachine? = null,
    ): Boolean {
        val resolvedMachine = machine ?: activeMachines[machineId]
        if (resolvedMachine == null) {
            sshDebugLog.record(
                machineId = machineId,
                tag = "Transport",
                message = "No machine profile for $machineId; did not connect",
                isError = true,
            )
            sshStateManager.setConnectionProgress(machineId, ConnectionProgress.Failed)
            return false
        }
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
        // A connect from the user removes the cause of an earlier stop.
        sshStateManager.clearHostKeyPrompt(machineId)
        sshStateManager.clearNeedsUser(machineId)
        if (!machine.authMethod.isSupported) {
            // The backend does not get the machine, so the app makes no network connection.
            sshDebugLog.record(
                machineId = machineId,
                tag = "SSHAuth",
                message = "Did not connect to ${machine.displayAddress}: $HARDWARE_KEY_NOT_SUPPORTED",
                isError = true,
            )
            failBeforeConnection(machineId, NeedsUserCause.Other(HARDWARE_KEY_NOT_SUPPORTED))
            return false
        }
        activeMachines[machineId] = machine
        surfaceLeaseActive[machineId] = true

        // Phase 1: AuthenticatingSsh (optionally via ProxyJump / Tailscale)
        sshStateManager.setConnectionProgress(machineId, ConnectionProgress.AuthenticatingSsh)
        val proxy = machine.proxyJump
        if (machine.hostname.isBlank() || !machine.isValidPort ||
            (proxy != null && (proxy.bastionHost.isBlank() || proxy.bastionPort !in 1..65535 || proxy.bastionHost.endsWith(".invalid")))
        ) {
            val message = "Invalid or unreachable SSH/Bastion endpoint for ${machine.displayAddress}"
            sshDebugLog.record(
                machineId = machineId,
                tag = "ProxyJump",
                message = message,
                isError = true,
            )
            failBeforeConnection(machineId, NeedsUserCause.Other(message))
            return false
        }
        return try {
            runConnectSteps(machine)
        } catch (error: Throwable) {
            if (error is CancellationException && !currentCoroutineContext().isActive) {
                // The caller stopped the connect. The progress must not stay at a connect step.
                stopForUser(machineId, cause = null)
                throw error
            }
            // A step that throws must not leave the progress at a connect step.
            sshDebugLog.record(
                machineId = machineId,
                tag = "Transport",
                message = "Connect to ${machine.displayAddress} failed: ${detailOf(error)}",
                isError = true,
            )
            stopForUser(machineId, causeOf(error))
            false
        }
    }

    /** Runs the connect steps after the checks of [connectInternal]. Returns false when a step fails. */
    private suspend fun runConnectSteps(machine: HostMachine): Boolean {
        val machineId = machine.id
        val route = proxyJumpTunnel.resolveRoute(machine)
        val authResult = backend.authenticate(machine, route)
        if (authResult != SshAuthResult.Success) {
            sshDebugLog.record(
                machineId = machineId,
                tag = "SSHAuth",
                message = "Login to ${machine.displayAddress} failed: ${describe(authResult)}",
                isError = true,
            )
            // The UI asks the user about a host key. The connection does not wait: the user connects again later.
            stopForUser(machineId, causeOf(authResult), hostKeyPromptFor(machineId, authResult))
            return false
        }

        // Phase 2: BootstrappingMoshOrBridge
        sshStateManager.setConnectionProgress(machineId, ConnectionProgress.BootstrappingMoshOrBridge)
        val backbone = backend.negotiateBackbone(machine, route)
        if (backbone == null) {
            sshDebugLog.record(
                machineId = machineId,
                tag = "Transport",
                message = NO_BACKBONE,
                isError = true,
            )
            stopForUser(machineId, NeedsUserCause.Other(NO_BACKBONE))
            return false
        }

        // Phase 4: SyncingHerdrSnapshot -> Connected
        sshStateManager.setConnectionProgress(machineId, ConnectionProgress.SyncingHerdrSnapshot)
        startControlSideband(machine, route, backbone)

        sshStateManager.markMachineConnected(machineId, backbone)
        flushOfflinePrompts(machineId)
        return true
    }

    /** Sets [cause] and [ConnectionProgress.Failed] for a connect that stopped before the backend got the machine. */
    private fun failBeforeConnection(machineId: String, cause: NeedsUserCause) {
        sshStateManager.setNeedsUser(machineId, cause)
        sshStateManager.setConnectionProgress(machineId, ConnectionProgress.Failed)
    }

    /**
     * Stops the connection of [machineId] after a failure. It releases the session and its resources, marks the
     * machine disconnected, puts [cause] and [hostKeyPrompt] in the state, and sets [ConnectionProgress.Failed].
     *
     * The machine stays in [activeMachines], so that a connect with no profile can use it again. The steps cannot
     * be cancelled, because the release can cancel the reconnect loop that calls this function.
     */
    private suspend fun stopForUser(
        machineId: String,
        cause: NeedsUserCause?,
        hostKeyPrompt: HostKeyPrompt? = null,
    ) = withContext(NonCancellable) {
        releaseConnection(machineId)
        sshStateManager.markMachineDisconnected(machineId)
        hostKeyPrompt?.let(sshStateManager::setHostKeyPrompt)
        cause?.let { sshStateManager.setNeedsUser(machineId, it) }
        sshStateManager.setConnectionProgress(machineId, ConnectionProgress.Failed)
    }

    /** Closes the session of [machineId], and releases its Mosh session, its surface fence, and its surface lease. */
    private suspend fun releaseConnection(machineId: String) {
        backend.disconnect(machineId)
        moshUdpEngine.stopSession(machineId)
        surfaceDeltaAssembler.clear(machineId)
        surfaceLeaseActive.remove(machineId)
    }

    /**
     * Disconnects [machineId], stopping any active Mosh UDP session and clearing cached control streams.
     */
    suspend fun disconnect(machineId: String) {
        releaseConnection(machineId)
        activeMachines.remove(machineId)
        sshStateManager.clearHostKeyPrompt(machineId)
        sshStateManager.clearNeedsUser(machineId)
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
     * Checks the sessions after a network change. `BisonSessionService` calls it when a default network becomes
     * available or when its link properties change. A [SshRequest.TriggerNetworkRoam] calls it for one machine.
     *
     * For [machineId], or for each connected machine when it is null, the manager does these steps. It does them
     * one time in each [NETWORK_CHECK_INTERVAL] for each machine, and ignores the calls between.
     * - On the Mosh backbone, it sends a wakeup datagram, so that `mosh-server` gets the new address of the client.
     * - It runs a liveness check with a timeout of [LIVENESS_TIMEOUT]: a keepalive message and one `exec true`. Each
     *   backbone uses the SSH session for its control commands, so the check runs for each backbone.
     * - When the check fails, it sets [RoamingStatus.RoamingReconnect] and asks the backend to reconnect now, with
     *   no delay. After the reconnect, the reconnect events restore the status and send the queued prompts.
     * - When the check passes, it restores the status and sends the queued prompts.
     */
    suspend fun onNetworkAvailable(machineId: String? = null) {
        val targets = if (machineId != null) {
            setOf(machineId)
        } else {
            sshStateManager.connectedMachines.value
        }

        for (id in targets) {
            if (!takeNetworkCheck(id)) continue
            val currentBackbone = sshStateManager.activeTransportBackbone.value[id]
            if (currentBackbone is ActiveBackbone.MoshUdp) {
                val roamedCount = moshUdpEngine.sendWakeupDatagram(id)
                sshStateManager.setBackbone(
                    machineId = id,
                    backbone = currentBackbone.copy(roamedCount = roamedCount),
                )
            }
            if (backend.checkLiveness(id, LIVENESS_TIMEOUT)) {
                sshDebugLog.record(
                    machineId = id,
                    tag = "Roaming",
                    message = "The session works on the new network",
                )
                if (sshStateManager.roamingState.value[id] == RoamingStatus.RoamingReconnect) {
                    sshStateManager.setRoamingStatus(id, statusAfterRoam(id))
                }
                flushOfflinePrompts(id)
            } else {
                sshDebugLog.record(
                    machineId = id,
                    tag = "Roaming",
                    message = "The session did not reply on the new network. The app connects again now.",
                    isError = true,
                )
                if (sshStateManager.isMachineConnected(id)) {
                    sshStateManager.setRoamingStatus(id, RoamingStatus.RoamingReconnect)
                }
                backend.reconnectNow(id)
            }
        }
    }

    /**
     * Closes the sessions after the device lost its default network. `BisonSessionService` calls it from `onLost`.
     *
     * For each connected machine, it sets [RoamingStatus.RoamingReconnect] and closes the sockets at once. Thus the
     * reconnect loop does not wait for a TCP timeout on the old network. The next [onNetworkAvailable] checks each
     * machine at once.
     */
    fun onNetworkLost() {
        for (id in sshStateManager.connectedMachines.value) {
            lastNetworkChecks.remove(id)
            sshStateManager.setRoamingStatus(id, RoamingStatus.RoamingReconnect)
            sshDebugLog.record(
                machineId = id,
                tag = "Roaming",
                message = "The device lost its network. The app closed the sockets.",
                isError = true,
            )
            backend.closeSockets(id)
        }
    }

    suspend fun triggerNetworkRoam(machineId: String? = null) {
        onNetworkAvailable(machineId)
    }

    /**
     * Returns true and records the time when [machineId] had no network check in the last [NETWORK_CHECK_INTERVAL].
     * Returns false when a check is too recent.
     */
    private fun takeNetworkCheck(machineId: String): Boolean {
        var taken = false
        lastNetworkChecks.compute(machineId) { _, last ->
            if (last == null || last.elapsedNow() >= NETWORK_CHECK_INTERVAL) {
                taken = true
                timeSource.markNow()
            } else {
                last
            }
        }
        return taken
    }

    /** Returns the status of a session that works: [RoamingStatus.SurfaceParked] while the lease is off. */
    private fun statusAfterRoam(machineId: String): RoamingStatus =
        if (surfaceLeaseActive[machineId] == false) RoamingStatus.SurfaceParked else RoamingStatus.Healthy

    /**
     * Restores [machineId] after the reconnect loop opened a new session. It sends `client_shell.surface.set
     * { active: true }` again when the surface lease is on, reads the surface again, and sends the queued prompts.
     */
    private suspend fun restoreAfterReconnect(machineId: String) {
        val machine = activeMachines[machineId] ?: return
        if (surfaceLeaseActive[machineId] == false) {
            sshStateManager.setRoamingStatus(machineId, RoamingStatus.SurfaceParked)
        } else {
            // This also sets RoamingStatus.Healthy, so that the queued prompts do not go back into the queue.
            setSurfaceLease(machineId, active = true)
        }
        try {
            backend.refreshSurface(machine, proxyJumpTunnel.resolveRoute(machine))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            sshDebugLog.record(
                machineId = machineId,
                tag = RECONNECT_LOG_TAG,
                message = "Could not read the surface after the reconnect: ${detailOf(error)}",
                isError = true,
            )
        }
        flushOfflinePrompts(machineId)
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
            is SshAuthResult.NetworkError -> "NetworkError (${detailOf(result.cause)})"
            is SshAuthResult.Failed -> "Failed (${detailOf(result.cause)})"
            is SshAuthResult.HostKeyUnknown ->
                "HostKeyUnknown (${result.presented.hostPort} ${result.presented.keyType} ${result.presented.fingerprint})"

            is SshAuthResult.HostKeyChanged ->
                "HostKeyChanged (${result.presented.hostPort} saved ${result.known.fingerprint}, " +
                    "sent ${result.presented.fingerprint})"

            is SshAuthResult.KeyMissing -> "KeyMissing (${result.message})"
        }

    /** Returns the host key prompt for [result], or null when [result] is not a host key rejection. */
    private fun hostKeyPromptFor(machineId: String, result: SshAuthResult): HostKeyPrompt? =
        when (result) {
            is SshAuthResult.HostKeyUnknown -> HostKeyPrompt.Unknown(machineId = machineId, presented = result.presented)
            is SshAuthResult.HostKeyChanged -> HostKeyPrompt.Changed(
                machineId = machineId,
                known = result.known,
                presented = result.presented,
            )

            else -> null
        }

    /** Returns the [NeedsUserCause] that the UI shows for the failed login [result]. */
    private fun causeOf(result: SshAuthResult): NeedsUserCause =
        when (result) {
            is SshAuthResult.AuthFailed -> NeedsUserCause.LoginRefused(result.message)
            is SshAuthResult.HostKeyUnknown -> NeedsUserCause.HostKeyUnknown(result.presented.hostPort)
            is SshAuthResult.HostKeyChanged -> NeedsUserCause.HostKeyChanged(result.presented.hostPort)
            is SshAuthResult.KeyMissing -> NeedsUserCause.KeyMissing(result.keyAlias)
            is SshAuthResult.NetworkError -> NeedsUserCause.Unreachable(detailOf(result.cause))
            is SshAuthResult.Failed -> causeOf(result.cause)
            // A login that passed needs nothing. Only an error in the caller gets here.
            SshAuthResult.Success -> NeedsUserCause.Other(describe(result))
        }

    /** Returns the [NeedsUserCause] that the UI shows for a connect step that threw [error]. */
    private fun causeOf(error: Throwable): NeedsUserCause {
        val chain = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
        return when {
            chain.any { it is MissingSecretException } -> NeedsUserCause.PasswordMissing
            chain.any { it is IOException } -> NeedsUserCause.Unreachable(detailOf(error))
            else -> NeedsUserCause.Other(detailOf(error))
        }
    }

    /** Returns the message of [error], or the name of its class when it has no message. */
    private fun detailOf(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "Unknown error"

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

        /** The maximum time of the liveness check of one session in [onNetworkAvailable]. */
        val LIVENESS_TIMEOUT: Duration = 3.seconds

        /** The minimum time between two network checks of one machine in [onNetworkAvailable]. */
        val NETWORK_CHECK_INTERVAL: Duration = 1.seconds

        /** The debug log tag of the reconnect loop. */
        internal const val RECONNECT_LOG_TAG: String = "Reconnect"

        /** The error text when no backbone that the transport mode of the machine permits is available. */
        internal const val NO_BACKBONE: String =
            "No transport that the transport mode of the machine permits is available"

        /** The maximum number of causes that the manager examines in an error. */
        private const val MAX_CAUSE_DEPTH = 16

        /**
         * The error text for a machine that uses [SshAuthMethod.HardwareSecurityKey]. This version does not
         * support that method. Issue #23 (https://github.com/ToxicBakery/BisonHerdr/issues/23) adds FIDO2 keys.
         */
        internal const val HARDWARE_KEY_NOT_SUPPORTED: String =
            "This version does not support hardware security keys (issue #23)"

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
