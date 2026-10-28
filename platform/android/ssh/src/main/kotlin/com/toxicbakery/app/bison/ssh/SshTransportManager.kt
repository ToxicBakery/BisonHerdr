package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.RoamingStatus
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Android SSH transport coordinator for Herdr hosts.
 *
 * Runs the connect steps of a machine (`Part IV.2` of `01-core-app-design.md` gives the target design):
 * 1. `AuthenticatingSsh` (via [AndroidKeystoreKeyManager] or `Password`, optionally via [ProxyJumpTunnel]).
 *    A machine with an [SshAuthMethod] that this version does not support fails before this phase (issue #23).
 * 2. `BootstrappingMoshOrBridge`: The backend selects the backbone ([SshBackend.negotiateBackbone]). This version
 *    has no Mosh transport (issue #22), so the backbone is always [ActiveBackbone.SshEndpoint]: the SSH session of
 *    the machine.
 * 3. `VerifyingUdpOrHello`: The backend looks for `mosh-server` on the host, except for an SSH-only machine. When
 *    the host has it, the backbone tells it in [ActiveBackbone.SshEndpoint.fallbackReason]. No Herdr hello occurs.
 * 4. `SyncingHerdrSnapshot`: The backend reads the Herdr snapshot and the visible panes, and starts its poll. The
 *    real backend runs `herdr api snapshot` and `herdr remote-api-bridge` requests (`pane.read`, `agent.prompt`,
 *    and others) on persistent `sh` channels of the SSH session. This version does not run
 *    `herdr remote-client-bridge`.
 * 5. Background parking ([setSurfaceLease]) and the session checks after a network change ([onNetworkAvailable]
 *    and [onNetworkLost]). While the surface lease is off, the poll reads no snapshot and no panes.
 *
 * The code for a Mosh backbone ([ActiveBackbone.MoshUdp] and [moshUdpEngine]) stays for issue #22, but the
 * backends never select it, and [MoshUdpEngine] starts no session.
 *
 * The manager keeps the connection state. [backend] does the work that uses the connection to the machine.
 *
 * After a connect, the backend reconnects on its own when the session closes, and sends the events of its loop to
 * the manager ([SessionEvents]). The manager shows [RoamingStatus.RoamingReconnect] during the loop. After a
 * reconnect, it sets the surface lease again, reads the surface again, and sends the queued prompts. When the loop
 * stops for the user, the manager puts the cause in [SshStateManager.needsUser] and marks the machine disconnected.
 *
 * [timeSource] measures the interval between two network checks of a machine. A test gives a source with virtual
 * time. [promptScope] sends the queued prompts after a network check, so that a slow prompt does not stop the next
 * network event. A test gives a scope with virtual time.
 */
class SshTransportManager(
    private val backend: SshBackend,
    private val moshUdpEngine: MoshUdpEngine,
    private val sshStateManager: SshStateManager,
    private val sshDebugLog: SshDebugLog,
    private val paneSurfaceBus: PaneSurfaceBus,
    var proxyJumpTunnel: ProxyJumpTunnel = ProxyJumpTunnel(sshDebugLog = sshDebugLog),
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val promptScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val surfaceDeltaAssembler = SurfaceDeltaAssembler()
    private val activeMachines = ConcurrentHashMap<String, HostMachine>()
    private val surfaceLeaseActive = ConcurrentHashMap<String, Boolean>()
    private val connectLocks = ConcurrentHashMap<String, Mutex>()
    private val presentationEpochCounter = AtomicLong(1L)

    /** The time of the last network check of each machine. See [onNetworkAvailable]. */
    private val lastNetworkChecks = ConcurrentHashMap<String, TimeMark>()

    /** Lets one prompt flush at a time run for each machine, so that the prompts go out in order. */
    private val flushLocks = ConcurrentHashMap<String, Mutex>()

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
            (proxy != null && (proxy.bastionHost.isBlank() || proxy.bastionPort !in 1..65535))
        ) {
            val message = "Invalid SSH or bastion endpoint for ${machine.displayAddress}"
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
     * Disconnects [machineId] and clears its session state in the manager. It also stops the Mosh session of the
     * machine, but this version starts no Mosh session (issue #22).
     */
    suspend fun disconnect(machineId: String) {
        releaseConnection(machineId)
        activeMachines.remove(machineId)
        sshStateManager.clearHostKeyPrompt(machineId)
        sshStateManager.clearNeedsUser(machineId)
        sshStateManager.cancelUserAuth(machineId)
        sshStateManager.markMachineDisconnected(machineId)
        sshDebugLog.record(
            machineId = machineId,
            tag = "Transport",
            message = "Disconnected session for $machineId",
        )
    }

    /**
     * Turns the surface lease of [machineId] on or off (`onStart()` -> `active = true`, `onStop()` -> `active = false`).
     *
     * - Sets the parked state of the Mosh session. This version has no Mosh session (issue #22).
     * - While the lease is off, the poll of the backend reads no snapshot and no panes. Thus the SSH session stays
     *   open with no terminal traffic. This version does not send `client_shell.surface.set` to the Herdr server.
     * - When the lease comes on (`active == true`), the manager publishes the last [PaneSurfaceFrame] again with a
     *   new presentation epoch, and the next poll reads the new state. The app does not send
     *   `endpoint.presentation.ready.v1` in this version.
     */
    suspend fun setSurfaceLease(machineId: String, active: Boolean) {
        surfaceLeaseActive[machineId] = active
        moshUdpEngine.setParked(machineId, parked = !active)
        val leaseRequest = HerdrRpcBuilder.clientShellSurfaceSet(active = active)
        val leaseText = if (active) {
            "on: the poll reads the snapshot and the panes"
        } else {
            "off: the poll reads no snapshot and no panes"
        }
        sshDebugLog.record(
            machineId = machineId,
            tag = "SurfaceLease",
            message = "Surface lease $leaseText. The app does not send ${leaseRequest.method} in this version.",
        )

        if (!active) {
            sshStateManager.setRoamingStatus(machineId, RoamingStatus.SurfaceParked)
        } else {
            val epoch = presentationEpochCounter.getAndIncrement().toULong()
            surfaceDeltaAssembler.onPresentationSync(machineId, epoch)
            // In the frame lock, a frame of the backend cannot occur between the read and the publish of the copy.
            val readyControl = backend.withFrameLock(machineId) {
                val currentSurface = paneSurfaceBus.surfaceFor(machineId) ?: return@withFrameLock null
                val assembled = surfaceDeltaAssembler.commitFullFrame(
                    machineId = machineId,
                    frame = currentSurface.copy(presentationEpoch = epoch),
                )
                paneSurfaceBus.publish(machineId, assembled.frame)
                assembled.presentationReadyControl
            }
            if (readyControl != null) {
                sshDebugLog.record(
                    machineId = machineId,
                    tag = "PresentationFence",
                    message = "Resumed: published the last frame again with presentation epoch $epoch",
                )
            }
            sshStateManager.setRoamingStatus(machineId, RoamingStatus.Healthy)
        }
    }

    /**
     * Sends a Herdr RPC to the backend of [machineId], and returns the response line of the backend. The real backend
     * sends each request as a `herdr remote-api-bridge` line.
     *
     * When the machine has no profile, or when the backend gives no response, the manager returns an error response
     * with the code [NO_RESPONSE_CODE]. Thus a caller cannot take a failed RPC for a success. A backend writes the
     * cause of its error to the debug log, so the manager writes an error only for a machine with no profile.
     */
    suspend fun sendEndpointCommand(
        machineId: String,
        request: ClientShellEndpointRequest,
    ): String {
        val isMoshActive = sshStateManager.activeTransportBackbone.value[machineId] is ActiveBackbone.MoshUdp
        sshDebugLog.record(
            machineId = machineId,
            tag = "RPC",
            message = HerdrRpcBuilder.toNdjsonLine(request).trim(),
        )
        val machine = activeMachines[machineId]
        if (machine == null) {
            sshDebugLog.record(
                machineId = machineId,
                tag = "RPC",
                message = "No machine profile for $machineId; did not send ${request.method}",
                isError = true,
            )
            return noResponseError(request, "The machine has no profile, so the app did not send ${request.method}")
        }
        return backend.sendEndpointCommand(machine, proxyJumpTunnel.resolveRoute(machine), request, isMoshActive)
            ?: noResponseError(request, "The backend gave no response for ${request.method}")
    }

    suspend fun sendEndpointCommand(
        machineId: String,
        method: String,
        paramsJson: String = "{}",
    ): String = sendEndpointCommand(
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
     * Sets the viewport size of [machineId] in the backend, and in the Mosh engine when it has a session. The real
     * backend draws the frame again for the new size from the last pane reads. It does not resize a terminal on the
     * host. This version has no Mosh session (issue #22).
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
            message = "Set the viewport to ${cols}x$rows (cell=${cellWidthPx}x${cellHeightPx}px)",
        )
    }

    /**
     * Checks the sessions after a network change. `BisonSessionService` calls it when a default network becomes
     * available or when its link properties change. A [SshRequest.TriggerNetworkRoam] calls it for one machine.
     *
     * For [machineId], or for each connected machine when it is null, the manager does these steps. It does them
     * one time in each [NETWORK_CHECK_INTERVAL] for each machine, and ignores the calls between. The machines get
     * their checks at the same time, and each check has its own timeout.
     * - On the Mosh backbone, it tells [MoshUdpEngine] about the network change. This version has no Mosh backbone
     *   (issue #22).
     * - It runs a liveness check with a timeout of [LIVENESS_TIMEOUT]: a keepalive message and one `exec true`.
     * - When the check fails, it sets [RoamingStatus.RoamingReconnect] and asks the backend to reconnect now, with
     *   no delay. It gives the session of the check, so that the backend keeps a newer session. After the
     *   reconnect, the reconnect events restore the status and send the queued prompts.
     * - When the check passes, it restores the status and sends the queued prompts in [promptScope]. A command can
     *   wait for a long time on a slow session, and the next network event must not wait for it.
     */
    suspend fun onNetworkAvailable(machineId: String? = null) {
        val targets = if (machineId != null) {
            setOf(machineId)
        } else {
            sshStateManager.connectedMachines.value
        }

        coroutineScope {
            for (id in targets) {
                if (takeNetworkCheck(id)) launch { checkAfterNetworkChange(id) }
            }
        }
    }

    /** Does the steps of [onNetworkAvailable] for [machineId]. */
    private suspend fun checkAfterNetworkChange(machineId: String) {
        val currentBackbone = sshStateManager.activeTransportBackbone.value[machineId]
        if (currentBackbone is ActiveBackbone.MoshUdp) {
            val roamedCount = moshUdpEngine.sendWakeupDatagram(machineId)
            sshStateManager.setBackbone(
                machineId = machineId,
                backbone = currentBackbone.copy(roamedCount = roamedCount),
            )
        }
        when (val liveness = backend.checkLiveness(machineId, LIVENESS_TIMEOUT)) {
            LivenessResult.Live -> {
                sshDebugLog.record(
                    machineId = machineId,
                    tag = "Roaming",
                    message = "The session works on the new network",
                )
                if (sshStateManager.roamingState.value[machineId] == RoamingStatus.RoamingReconnect) {
                    sshStateManager.setRoamingStatus(machineId, statusAfterRoam(machineId))
                }
                launchPromptFlush(machineId)
            }

            is LivenessResult.Dead -> {
                sshDebugLog.record(
                    machineId = machineId,
                    tag = "Roaming",
                    message = "The session did not reply on the new network. The app connects again now.",
                    isError = true,
                )
                if (sshStateManager.isMachineConnected(machineId)) {
                    sshStateManager.setRoamingStatus(machineId, RoamingStatus.RoamingReconnect)
                }
                backend.reconnectNow(machineId, liveness.sessionId)
            }
        }
    }

    /**
     * Closes the sessions after the device lost its default network. `BisonSessionService` calls it from `onLost`.
     *
     * For each connected machine, it sets [RoamingStatus.RoamingReconnect] and closes the sockets at once. Thus no
     * attempt waits for a TCP timeout on the old network. The reconnect loop then makes no attempt until the next
     * [onNetworkAvailable], which checks each machine at once.
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
     * Restores [machineId] after the reconnect loop opened a new session. When the surface lease is on, it publishes
     * the last frame again and sets [RoamingStatus.Healthy]. When the lease is off, it keeps
     * [RoamingStatus.SurfaceParked]. Then it reads the surface again and sends the queued prompts.
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
        sshDebugLog.record(
            machineId = machineId,
            tag = "Surface",
            message = "The backend reads the Herdr snapshot and the visible panes, and then polls them while the " +
                "surface lease is on. The real backend runs herdr api snapshot and ${buildSshApiBridgeCommand()}.",
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
            is SshAuthResult.UserAuthRequired -> "UserAuthRequired (${result.message})"
            is SshAuthResult.InputRequired ->
                "InputRequired (${result.hostPort}, ${if (result.timedOut) "time limit" else "no UI"})"

            is SshAuthResult.InputCancelled -> "InputCancelled (${result.hostPort})"
            is SshAuthResult.LocalNetworkBlocked -> "LocalNetworkBlocked (${result.hostPort})"
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
            is SshAuthResult.UserAuthRequired -> {
                val keyAlias = result.keyAlias
                if (result.keyNeedsUserForEachSign && keyAlias != null) {
                    NeedsUserCause.KeyFromEarlierVersion(keyAlias)
                } else {
                    NeedsUserCause.UserAuthRequired(keyAlias)
                }
            }
            is SshAuthResult.InputRequired -> causeOf(result)
            is SshAuthResult.InputCancelled -> NeedsUserCause.InputCancelled(result.hostPort)
            is SshAuthResult.LocalNetworkBlocked -> NeedsUserCause.LocalNetworkPermission(result.hostPort)
            is SshAuthResult.NetworkError -> NeedsUserCause.Unreachable(detailOf(result.cause))
            is SshAuthResult.Failed -> causeOf(result.cause)
            // A login that passed needs nothing. Only an error in the caller gets here.
            SshAuthResult.Success -> NeedsUserCause.Other(describe(result))
        }

    /** Returns the [NeedsUserCause] that the UI shows for a login that needs answers from the user. */
    private fun causeOf(result: SshAuthResult.InputRequired): NeedsUserCause =
        if (result.timedOut) {
            NeedsUserCause.InputTimedOut(result.hostPort)
        } else {
            NeedsUserCause.InputRequired(result.hostPort)
        }

    /** Returns the [NeedsUserCause] that the UI shows for a connect step that threw [error]. */
    private fun causeOf(error: Throwable): NeedsUserCause {
        val chain = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
        val userInput = chain.filterIsInstance<UserInputException>().firstOrNull()
        return when {
            userInput != null -> causeOf(userInput.result)
            chain.any { it is MissingSecretException } -> NeedsUserCause.PasswordMissing
            chain.any { it is IOException } -> NeedsUserCause.Unreachable(detailOf(error))
            else -> NeedsUserCause.Other(detailOf(error))
        }
    }

    /** Returns the message of [error], or the name of its class when it has no message. */
    private fun detailOf(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "Unknown error"

    /** Sends the queued prompts of [machineId] in [promptScope], and does not wait for them. */
    private fun launchPromptFlush(machineId: String) {
        promptScope.launch {
            try {
                flushOfflinePrompts(machineId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                sshDebugLog.record(
                    machineId = machineId,
                    tag = "PromptQueue",
                    message = "Could not send the queued prompts: ${detailOf(error)}",
                    isError = true,
                )
            }
        }
    }

    /**
     * Sends the queued prompts of [machineId]. Only one flush runs at a time for a machine. Each flush removes its
     * prompts from the queue before it sends them, so two flushes cannot send the same prompt.
     */
    private suspend fun flushOfflinePrompts(machineId: String) =
        flushLocks.computeIfAbsent(machineId) { Mutex() }.withLock {
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
        const val REMOTE_TABLET_CONFIG_PATH: String = "/tmp/herdr-bison-tablet.toml"
        /**
         * The error code of the response of [sendEndpointCommand] when the machine has no profile, or when the backend
         * gives no response.
         */
        const val NO_RESPONSE_CODE: String = "no_response"
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
         * No code runs this command in this version. Issue #22 adds the Mosh path.
         */
        fun buildMoshServerBootstrapCommand(machine: HostMachine): String {
            val moshBin = machine.moshServerPath?.takeIf { it.isNotBlank() } ?: "mosh-server"
            val portRange = machine.moshUdpPortRange.ifBlank { HostMachine.DEFAULT_MOSH_UDP_PORT_RANGE }
            val sessionArg = machine.herdrSessionName?.takeIf { it.isNotBlank() }?.let { " --session $it" }.orEmpty()
            return "command -v $moshBin >/dev/null 2>&1 && " +
                "$moshBin new -s -c 256 -l LANG=en_US.UTF-8 -p $portRange -- " +
                "env HERDR_CONFIG_PATH=$REMOTE_TABLET_CONFIG_PATH herdr$sessionArg"
        }

        /** Returns the error response line of [request] with the code [NO_RESPONSE_CODE] and [message]. */
        internal fun noResponseError(request: ClientShellEndpointRequest, message: String): String =
            buildJsonObject {
                put("id", request.id)
                putJsonObject("error") {
                    put("code", NO_RESPONSE_CODE)
                    put("message", message)
                }
            }.toString()

        fun buildSshApiBridgeCommand(): String = "herdr remote-api-bridge"
    }
}
