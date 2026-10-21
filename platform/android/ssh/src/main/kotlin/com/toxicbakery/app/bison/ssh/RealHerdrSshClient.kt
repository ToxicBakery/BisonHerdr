package com.toxicbakery.app.bison.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.protocol.CellData
import com.toxicbakery.app.bison.protocol.ClientKeyCode
import com.toxicbakery.app.bison.protocol.ClientKeyEvent
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
import com.toxicbakery.app.bison.protocol.PaneLayoutPane
import com.toxicbakery.app.bison.protocol.PaneLayoutSnapshot
import com.toxicbakery.app.bison.protocol.PaneLayoutSplit
import com.toxicbakery.app.bison.protocol.PaneSurfaceFrame
import com.toxicbakery.app.bison.protocol.PaneSurfacePane
import com.toxicbakery.app.bison.protocol.PaneSurfaceScroll
import com.toxicbakery.app.bison.protocol.PaneSurfaceSplitDirection
import com.toxicbakery.app.bison.protocol.SurfaceRect
import com.toxicbakery.app.bison.protocol.deriveSplitsFromLayout
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Live SSH2 client (powered by JSch) that connects to unmodified upstream Herdr servers,
 * fetches real `herdr api snapshot` topology (workspaces, tabs, panes, coding agents, BSP layouts),
 * streams visible ANSI pane buffers via `herdr remote-api-bridge` (`pane.read`), and dispatches
 * real interactive keystrokes (`pane.send_text`, `pane.send_keys`, `pane.send_input`), prompts,
 * and workspace/tab/pane focus mutations.
 *
 * Uses two dedicated persistent POSIX `sh` worker channels per machine ([WorkerLane.Input] and [WorkerLane.Poll])
 * over `TCP_NODELAY` sockets with client-side optimistic local echo (`<1ms`), keystroke coalescing, and
 * focused-pane-only reads so interactive typing never spawns per-key SSH channels or blocks behind background polls.
 */
internal class RealHerdrSshClient(
    private val sshDebugLog: SshDebugLog,
    private val endpointSnapshotBus: EndpointSnapshotBus,
    private val paneSurfaceBus: PaneSurfaceBus,
    private val semanticNotificationBus: SemanticNotificationBus,
    private val keystoreKeyManagerProvider: () -> AndroidKeystoreKeyManager = { AndroidKeystoreKeyManager() },
    private val remoteBatchExecutor: (
        suspend (
            machine: HostMachine,
            route: ResolvedSshRoute,
            lane: WorkerLane,
            commands: List<RemoteShellCommand>,
        ) -> String
    )? = null,
) {
    internal enum class WorkerLane {
        Input,
        Poll,
    }

    internal sealed interface RemoteShellCommand {
        data class Exec(val command: String) : RemoteShellCommand
        data class ApiBridgeLine(val ndjsonLine: String) : RemoteShellCommand
    }

    internal data class PendingPaneInput(
        val seq: Long,
        val paneId: String,
        val event: ClientPaneInputEvent,
    )

    internal sealed interface OptimisticEdit {
        val seq: Long
        val paneId: String
        val createdAtMs: Long

        data class InsertText(
            override val seq: Long,
            override val paneId: String,
            val text: String,
            override val createdAtMs: Long = System.currentTimeMillis(),
        ) : OptimisticEdit

        data class Backspace(
            override val seq: Long,
            override val paneId: String,
            override val createdAtMs: Long = System.currentTimeMillis(),
        ) : OptimisticEdit

        data class Newline(
            override val seq: Long,
            override val paneId: String,
            override val createdAtMs: Long = System.currentTimeMillis(),
        ) : OptimisticEdit
    }

    private data class ClientSelectionState(
        @Volatile var focusedWorkspaceId: String? = null,
        @Volatile var focusedTabId: String? = null,
        @Volatile var focusedPaneId: String? = null,
    )

    private class SgrState(
        val fg: UInt,
        val bg: UInt,
        val mod: UShort,
    )

    private data class CachedSurfaceInputs(
        val snapshot: ClientShellSnapshot,
        val layout: PaneLayoutSnapshot?,
        val ansiByPane: Map<String, String>,
    )

    private class PersistentShellWorker(
        val channel: ChannelExec,
        val stdin: OutputStream,
        val stdout: BufferedReader,
    ) {
        val isAlive: Boolean
            get() = !channel.isClosed && channel.isConnected

        fun close() {
            runCatching { stdin.close() }
            runCatching { stdout.close() }
            runCatching { channel.disconnect() }
        }
    }

    private object LowLatencySocketFactory : SocketFactory {
        override fun createSocket(host: String, port: Int): Socket =
            Socket().apply {
                tcpNoDelay = true
                keepAlive = true
                runCatching { trafficClass = IPTOS_LOWDELAY }
                connect(InetSocketAddress(host, port), SOCKET_CONNECT_TIMEOUT_MS)
            }

        override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()

        override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val sessionOpenLocks = ConcurrentHashMap<String, Mutex>()
    private val inputLaneLocks = ConcurrentHashMap<String, Mutex>()
    private val pollLaneLocks = ConcurrentHashMap<String, Mutex>()
    private val inputWorkers = ConcurrentHashMap<String, PersistentShellWorker>()
    private val pollWorkers = ConcurrentHashMap<String, PersistentShellWorker>()
    private val pollJobs = ConcurrentHashMap<String, Job>()
    private val inputFlushJobs = ConcurrentHashMap<String, Job>()
    private val inputFlushSignals = ConcurrentHashMap<String, Channel<Unit>>()
    private val pendingInputQueues = ConcurrentHashMap<String, ConcurrentLinkedQueue<PendingPaneInput>>()
    private val optimisticEdits = ConcurrentHashMap<String, ArrayDeque<OptimisticEdit>>()
    private val selectionState = ConcurrentHashMap<String, ClientSelectionState>()
    private val cachedInputs = ConcurrentHashMap<String, CachedSurfaceInputs>()
    private val viewportSizes = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val revisionCounter = AtomicLong(10L)
    private val inputSequence = AtomicLong(0L)
    private val lastInputConfirmedSeq = ConcurrentHashMap<String, Long>()
    private val shellBatchCounter = AtomicLong(1L)

    /** Scroll position of each pane from the Herdr snapshot, by machine and then by pane. */
    private val paneScrolls = ConcurrentHashMap<String, Map<String, PaneSurfaceScroll>>()

    /** The newest `pane.scroll` request of each machine that is not sent yet. Older requests are dropped. */
    private val pendingPaneScrolls = ConcurrentHashMap<String, PendingPaneScroll>()
    private val paneScrollSignals = ConcurrentHashMap<String, Channel<Unit>>()
    private val paneScrollJobs = ConcurrentHashMap<String, Job>()

    private data class PendingPaneScroll(val paneId: String, val offsetFromBottom: Int)

    fun isSimulatedHost(machine: HostMachine): Boolean {
        val host = machine.hostname.trim().lowercase()
        val bastion = machine.proxyJump?.bastionHost?.trim()?.lowercase().orEmpty()
        if (host.isEmpty()) return true
        if (host.endsWith(".local") || host.endsWith(".example.com") || host.endsWith(".invalid")) return true
        if (host.endsWith(".ts.net") || host.startsWith("100.64.")) return true
        if (bastion.endsWith(".example.com") || bastion.endsWith(".invalid") || bastion.startsWith("100.64.")) return true
        return false
    }

    private fun sessionMutexFor(machineId: String): Mutex =
        sessionOpenLocks.computeIfAbsent(machineId) { Mutex() }

    private fun laneMutexFor(machineId: String, lane: WorkerLane): Mutex =
        when (lane) {
            WorkerLane.Input -> inputLaneLocks.computeIfAbsent(machineId) { Mutex() }
            WorkerLane.Poll -> pollLaneLocks.computeIfAbsent(machineId) { Mutex() }
        }

    private fun inputQueueFor(machineId: String): ConcurrentLinkedQueue<PendingPaneInput> =
        pendingInputQueues.computeIfAbsent(machineId) { ConcurrentLinkedQueue() }

    private fun optimisticEditsFor(machineId: String): ArrayDeque<OptimisticEdit> =
        optimisticEdits.computeIfAbsent(machineId) { ArrayDeque() }

    suspend fun connectAndVerify(
        machine: HostMachine,
        route: ResolvedSshRoute,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val out = executeBatchOnLane(
                machine = machine,
                route = route,
                lane = WorkerLane.Poll,
                commands = listOf(RemoteShellCommand.Exec("herdr --version || true")),
            )
            sshDebugLog.record(
                machineId = machine.id,
                tag = "RealSSH",
                message = "Connected to ${route.entryHost}:${route.entryPort} (${out.trim().ifBlank { "SSH ok" }})",
            )
            true
        }.getOrElse { err ->
            val errDetail = err.message?.takeIf { it.isNotBlank() }
                ?: err::class.simpleName
                ?: "Unknown SSH error"
            sshDebugLog.record(
                machineId = machine.id,
                tag = "RealSSH",
                message = "SSH connection failed to ${route.entryHost}:${route.entryPort}: $errDetail",
                isError = true,
            )
            false
        }
    }

    suspend fun checkMoshServerAvailable(
        machine: HostMachine,
        route: ResolvedSshRoute,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val moshBin = machine.moshServerPath?.takeIf { it.isNotBlank() } ?: "mosh-server"
            val out = executeBatchOnLane(
                machine = machine,
                route = route,
                lane = WorkerLane.Poll,
                commands = listOf(RemoteShellCommand.Exec("command -v $moshBin")),
            )
            out.trim().isNotEmpty()
        }.getOrDefault(false)
    }

    fun startLivePolling(
        machine: HostMachine,
        route: ResolvedSshRoute,
        isSurfaceActive: () -> Boolean,
    ) {
        pollJobs.remove(machine.id)?.cancel()
        val job = scope.launch {
            while (isActive) {
                if (isSurfaceActive()) {
                    runCatching {
                        refreshSnapshotAndSurface(machine, route)
                    }.onFailure { err ->
                        sshDebugLog.record(
                            machineId = machine.id,
                            tag = "RealSSH",
                            message = "Live poll warning: ${err.message}",
                            isError = true,
                        )
                    }
                }
                delay(LIVE_POLL_INTERVAL_MS)
            }
        }
        pollJobs[machine.id] = job
    }

    fun disconnect(machineId: String) {
        pollJobs.remove(machineId)?.cancel()
        paneScrollJobs.remove(machineId)?.cancel()
        paneScrollSignals.remove(machineId)?.close()
        pendingPaneScrolls.remove(machineId)
        paneScrolls.remove(machineId)
        inputFlushJobs.remove(machineId)?.cancel()
        inputFlushSignals.remove(machineId)?.close()
        pendingInputQueues.remove(machineId)?.clear()
        optimisticEdits.remove(machineId)?.let { synchronized(it) { it.clear() } }
        lastInputConfirmedSeq.remove(machineId)
        selectionState.remove(machineId)
        cachedInputs.remove(machineId)
        viewportSizes.remove(machineId)
        inputWorkers.remove(machineId)?.close()
        pollWorkers.remove(machineId)?.close()
        sessions.remove(machineId)?.let { session ->
            runCatching { session.disconnect() }
        }
    }

    fun resizeViewport(
        machineId: String,
        cols: UShort,
        rows: UShort,
    ) {
        val targetCols = cols.toInt().coerceIn(20, 300)
        val targetRows = rows.toInt().coerceIn(8, 150)
        val prev = viewportSizes.put(machineId, targetCols to targetRows)
        if (prev == (targetCols to targetRows)) return
        val cached = cachedInputs[machineId] ?: return
        val frame = buildCompositeSurfaceFrame(
            machineId = machineId,
            snapshot = cached.snapshot,
            layout = cached.layout,
            ansiByPane = cached.ansiByPane,
        )
        paneSurfaceBus.publish(machineId, frame)
    }

    suspend fun refreshSnapshotAndSurface(
        machine: HostMachine,
        route: ResolvedSshRoute,
    ): Boolean = withContext(Dispatchers.IO) {
        val pollStartInputSeq = inputSequence.get()
        val pollStartConfirmedSeq = lastInputConfirmedSeq[machine.id] ?: 0L
        val predictedVisiblePanes = cachedInputs[machine.id]?.let { existing ->
            val activeTabId = existing.snapshot.focusedTabId
            existing.layout?.panes?.map { it.paneId }?.takeIf { it.isNotEmpty() }
                ?: existing.snapshot.panes
                    .filter { activeTabId == null || it.tabId == activeTabId }
                    .map { it.paneId }
                    .ifEmpty { listOfNotNull(existing.snapshot.focusedPaneId) }
        }.orEmpty()

        val initialCommands = buildList {
            add(RemoteShellCommand.Exec("herdr api snapshot"))
            predictedVisiblePanes.forEach { paneId ->
                add(RemoteShellCommand.ApiBridgeLine(buildPaneReadJsonLine(paneId)))
            }
        }

        val combinedRawOutput = executeBatchOnLane(
            machine = machine,
            route = route,
            lane = WorkerLane.Poll,
            commands = initialCommands,
        )

        var snapshotObj: JsonObject? = null
        val ansiByPane = mutableMapOf<String, String>()

        combinedRawOutput.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") && it.endsWith("}") }
            .forEach { line ->
                runCatching {
                    val obj = json.parseToJsonElement(line).jsonObject
                    val resultObj = obj["result"]?.jsonObject ?: return@forEach
                    val snapCandidate = resultObj["snapshot"]?.jsonObject
                    if (snapCandidate != null && snapshotObj == null) {
                        snapshotObj = snapCandidate
                    }
                    val readObj = resultObj["read"]?.jsonObject
                    if (readObj != null) {
                        val pId = readObj["pane_id"]?.jsonPrimitive?.content
                        val text = readObj["text"]?.jsonPrimitive?.content
                        if (!pId.isNullOrBlank() && text != null) {
                            ansiByPane[pId] = text
                        }
                    }
                }
            }

        val resolvedSnapshotObj = snapshotObj ?: return@withContext false
        val serverScrolls = parsePaneScrolls(resolvedSnapshotObj)
        // A queued scroll position is newer than the snapshot, so a poll must not move the pane back.
        paneScrolls[machine.id] = pendingPaneScrolls[machine.id]?.let { pending ->
            val scroll = serverScrolls[pending.paneId] ?: PaneSurfaceScroll()
            serverScrolls + (pending.paneId to scroll.copy(offsetFromBottom = pending.offsetFromBottom.toUInt()))
        } ?: serverScrolls

        val sel = selectionState.computeIfAbsent(machine.id) { ClientSelectionState() }
        val parsed = parseSnapshotObject(machine.id, resolvedSnapshotObj, sel)
        endpointSnapshotBus.publish(machine.id, parsed.snapshot)

        val activeTabId = parsed.snapshot.focusedTabId
        val activeLayout = parsed.layoutsByTab[activeTabId]
            ?: parsed.layoutsByTab.values.firstOrNull()
        val visiblePaneIds = activeLayout?.panes?.map { it.paneId }
            ?.takeIf { it.isNotEmpty() }
            ?: parsed.snapshot.panes
                .filter { activeTabId == null || it.tabId == activeTabId }
                .map { it.paneId }
                .ifEmpty { listOfNotNull(parsed.snapshot.focusedPaneId) }

        if (visiblePaneIds.isEmpty()) return@withContext true

        val missingPaneIds = visiblePaneIds.filter { it !in ansiByPane }
        if (missingPaneIds.isNotEmpty()) {
            val missingCommands = missingPaneIds.map { paneId ->
                RemoteShellCommand.ApiBridgeLine(buildPaneReadJsonLine(paneId))
            }
            val missingResponse = executeBatchOnLane(
                machine = machine,
                route = route,
                lane = WorkerLane.Poll,
                commands = missingCommands,
            )
            parsePaneReadLinesInto(missingResponse, ansiByPane)
        }

        // If the fast input lane updated the focused pane while this background poll was in flight,
        // keep the newer focused-pane ANSI buffer so polling never clobbers recent keystrokes.
        val currentConfirmedSeq = lastInputConfirmedSeq[machine.id] ?: 0L
        val currentInputSeq = inputSequence.get()
        if (currentConfirmedSeq > pollStartConfirmedSeq || currentInputSeq > pollStartInputSeq) {
            val focusedId = parsed.snapshot.focusedPaneId
            val newerCachedAnsi = focusedId?.let { cachedInputs[machine.id]?.ansiByPane?.get(it) }
            if (focusedId != null && newerCachedAnsi != null) {
                ansiByPane[focusedId] = newerCachedAnsi
            }
        }

        // Update agent snippets from the read pane buffers
        parsed.snapshot.agents.forEach { agent ->
            val rawText = ansiByPane[agent.paneId]
            if (!rawText.isNullOrBlank()) {
                val cleanSnippet = stripAnsiCodes(rawText)
                    .lineSequence()
                    .map { it.trimEnd() }
                    .filter { it.isNotBlank() }
                    .toList()
                    .takeLast(4)
                    .joinToString("\n")
                if (cleanSnippet.isNotBlank()) {
                    semanticNotificationBus.updateAgentSnippet(
                        machineId = machine.id,
                        paneId = agent.paneId,
                        snippet = cleanSnippet,
                    )
                }
            }
        }

        cachedInputs[machine.id] = CachedSurfaceInputs(
            snapshot = parsed.snapshot,
            layout = activeLayout,
            ansiByPane = ansiByPane,
        )

        val frame = buildCompositeSurfaceFrame(
            machineId = machine.id,
            snapshot = parsed.snapshot,
            layout = activeLayout,
            ansiByPane = ansiByPane,
        )
        paneSurfaceBus.publish(machine.id, frame)
        true
    }

    private fun parseParamsObject(paramsJson: String): JsonObject =
        runCatching { json.parseToJsonElement(paramsJson).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }

    suspend fun executeEndpointRpc(
        machine: HostMachine,
        route: ResolvedSshRoute,
        request: ClientShellEndpointRequest,
    ): String? = withContext(Dispatchers.IO) {
        val sel = selectionState.computeIfAbsent(machine.id) { ClientSelectionState() }

        // Track client selection overrides immediately so switching workspaces/tabs/panes is instant
        when (request.method) {
            "workspace.focus" -> {
                val paramsObj = parseParamsObject(request.paramsJson)
                val wsId = paramsObj["workspace_id"]?.jsonPrimitive?.content
                if (!wsId.isNullOrBlank()) {
                    sel.focusedWorkspaceId = wsId
                    val currentSnap = endpointSnapshotBus.snapshotFor(machine.id)
                    val ws = currentSnap?.workspaces?.firstOrNull { it.workspaceId == wsId }
                    val targetTabId = ws?.activeTabId
                        ?: currentSnap?.tabs?.firstOrNull { it.workspaceId == wsId }?.tabId
                    if (!targetTabId.isNullOrBlank()) {
                        sel.focusedTabId = targetTabId
                        val targetPaneId = currentSnap?.panes
                            ?.firstOrNull { it.tabId == targetTabId }?.paneId
                        if (!targetPaneId.isNullOrBlank()) {
                            sel.focusedPaneId = targetPaneId
                        }
                    }
                }
            }

            "tab.focus" -> {
                val paramsObj = parseParamsObject(request.paramsJson)
                val tabId = paramsObj["tab_id"]?.jsonPrimitive?.content
                if (!tabId.isNullOrBlank()) {
                    sel.focusedTabId = tabId
                    val currentSnap = endpointSnapshotBus.snapshotFor(machine.id)
                    val tab = currentSnap?.tabs?.firstOrNull { it.tabId == tabId }
                    if (tab != null) {
                        sel.focusedWorkspaceId = tab.workspaceId
                    }
                    val targetPaneId = currentSnap?.panes?.firstOrNull { it.tabId == tabId }?.paneId
                    if (!targetPaneId.isNullOrBlank()) {
                        sel.focusedPaneId = targetPaneId
                    }
                }
            }

            "pane.focus", "agent.focus" -> {
                val paramsObj = parseParamsObject(request.paramsJson)
                val paneId = paramsObj["pane_id"]?.jsonPrimitive?.content
                if (!paneId.isNullOrBlank()) {
                    sel.focusedPaneId = paneId
                    val currentSnap = endpointSnapshotBus.snapshotFor(machine.id)
                    val pane = currentSnap?.panes?.firstOrNull { it.paneId == paneId }
                    if (pane != null) {
                        sel.focusedWorkspaceId = pane.workspaceId
                        sel.focusedTabId = pane.tabId
                    }
                    applyOptimisticPaneFocus(machine.id, paneId)
                }
            }
        }

        // Scroll gestures send one request per line. Keep only the newest request, so that the pane
        // follows the finger, and do not refresh the full snapshot for each line.
        if (request.method == "pane.scroll") {
            val paramsObj = parseParamsObject(request.paramsJson)
            val paneId = paramsObj["pane_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val offset = paramsObj["offset_from_bottom"]?.jsonPrimitive?.intOrNull
            if (paneId != null && offset != null) {
                requestPaneScroll(machine, route, paneId, offset)
                return@withContext PANE_SCROLL_ACCEPTED_RESPONSE
            }
        }

        // Fast-path key/input RPCs (e.g., sendKeyChord -> pane.send_keys) onto the Input lane
        // without running a full topology snapshot.
        val isPaneInputRpc = request.method == "pane.send_keys" ||
            request.method == "pane.send_text" ||
            request.method == "pane.send_input"
        if (isPaneInputRpc) {
            val paramsObj = parseParamsObject(request.paramsJson)
            val targetPaneId = paramsObj["pane_id"]?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?: sel.focusedPaneId
                ?: endpointSnapshotBus.snapshotFor(machine.id)?.focusedPaneId
            val ndjsonLine = HerdrRpcBuilder.toNdjsonLine(request).trim()
            return@withContext runCatching {
                val cmds = buildList {
                    add(RemoteShellCommand.ApiBridgeLine(ndjsonLine))
                    if (!targetPaneId.isNullOrBlank()) {
                        add(RemoteShellCommand.ApiBridgeLine(buildPaneReadJsonLine(targetPaneId)))
                    }
                }
                val out = executeBatchOnLane(machine, route, WorkerLane.Input, cmds)
                if (!targetPaneId.isNullOrBlank()) {
                    val updatedAnsi = extractPaneAnsiFromResponse(out, targetPaneId)
                    if (updatedAnsi != null) {
                        updateFocusedPaneSurfaceFromInput(
                            machineId = machine.id,
                            paneId = targetPaneId,
                            paneAnsi = updatedAnsi,
                            confirmedSeq = inputSequence.get(),
                        )
                    }
                }
                out
            }.getOrElse { err ->
                sshDebugLog.record(
                    machineId = machine.id,
                    tag = "RealSSH",
                    message = "RPC ${request.method} error: ${err.message}",
                    isError = true,
                )
                null
            }
        }

        val ndjsonLine = HerdrRpcBuilder.toNdjsonLine(request).trim()
        val response = runCatching {
            executeBatchOnLane(
                machine = machine,
                route = route,
                lane = WorkerLane.Poll,
                commands = listOf(RemoteShellCommand.ApiBridgeLine(ndjsonLine)),
            )
        }.getOrElse { err ->
            sshDebugLog.record(
                machineId = machine.id,
                tag = "RealSSH",
                message = "RPC ${request.method} error: ${err.message}",
                isError = true,
            )
            null
        }

        // Refresh snapshot and visible pane ANSI buffer after structural/focus RPCs
        runCatching { refreshSnapshotAndSurface(machine, route) }
        response
    }

    /**
     * Dispatches a pane input event with:
     * 1. Immediate (`<1ms`) optimistic local echo published onto [paneSurfaceBus].
     * 2. Non-blocking enqueue into the per-machine coalescing input queue so rapid typing never stalls
     *    behind sequential round-trips.
     * 3. Single-pass execution on the persistent [WorkerLane.Input] channel (`pane.send_text` / `pane.send_keys` /
     *    `pane.send_input` + focused `pane.read`), completely decoupled from background polling.
     */
    suspend fun sendPaneInput(
        machine: HostMachine,
        route: ResolvedSshRoute,
        paneId: String,
        event: ClientPaneInputEvent,
    ): Boolean {
        val targetPaneId = paneId.takeIf { it.isNotBlank() }
            ?: selectionState[machine.id]?.focusedPaneId
            ?: endpointSnapshotBus.snapshotFor(machine.id)?.focusedPaneId
            ?: paneSurfaceBus.surfaceFor(machine.id)?.panes?.firstOrNull { it.focused }?.paneId
            ?: return false

        if (event is ClientPaneInputEvent.Mouse) {
            return true
        }

        // Herdr keeps the scroll position when input arrives. Go back to the bottom, like a terminal,
        // so that the user sees the prompt and the echo.
        if ((paneScrolls[machine.id]?.get(targetPaneId)?.offsetFromBottom ?: 0u) > 0u) {
            requestPaneScroll(machine, route, targetPaneId, 0)
        }

        val seq = inputSequence.incrementAndGet()

        // 1. Apply immediate optimistic local echo (<1ms) so typed characters appear on screen before network I/O
        val optimisticEdit = toOptimisticEdit(seq, targetPaneId, event)
        if (optimisticEdit != null) {
            val edits = optimisticEditsFor(machine.id)
            synchronized(edits) {
                while (edits.size >= MAX_OPTIMISTIC_EDITS) {
                    edits.removeFirst()
                }
                edits.addLast(optimisticEdit)
            }
            val currentFrame = paneSurfaceBus.surfaceFor(machine.id)
            if (currentFrame != null) {
                val updatedFrame = applyOptimisticEditToFrame(currentFrame, optimisticEdit)
                paneSurfaceBus.publish(machine.id, updatedFrame)
            }
        } else if (isControlInterruptEvent(event)) {
            val edits = optimisticEditsFor(machine.id)
            synchronized(edits) {
                edits.clear()
            }
        }

        // 2. Enqueue for coalesced transmission on the dedicated persistent Input lane
        inputQueueFor(machine.id).add(
            PendingPaneInput(
                seq = seq,
                paneId = targetPaneId,
                event = event,
            )
        )
        triggerInputFlushLoop(machine, route)
        return true
    }

    /**
     * Suspends until all currently queued input events for [machineId] have been flushed over [WorkerLane.Input].
     */
    internal suspend fun awaitInputFlush(machineId: String) = withContext(Dispatchers.IO) {
        val targetSeq = inputSequence.get()
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val confirmed = lastInputConfirmedSeq[machineId] ?: 0L
            val queueEmpty = inputQueueFor(machineId).isEmpty()
            val mutexUnlocked = !laneMutexFor(machineId, WorkerLane.Input).isLocked
            if (confirmed >= targetSeq && queueEmpty && mutexUnlocked) {
                return@withContext
            }
            delay(5L)
        }
    }

    private fun triggerInputFlushLoop(
        machine: HostMachine,
        route: ResolvedSshRoute,
    ) {
        val signal = inputFlushSignals.computeIfAbsent(machine.id) {
            Channel(Channel.CONFLATED)
        }
        val existingJob = inputFlushJobs[machine.id]
        if (existingJob == null || !existingJob.isActive) {
            synchronized(inputFlushJobs) {
                val current = inputFlushJobs[machine.id]
                if (current == null || !current.isActive) {
                    inputFlushJobs[machine.id] = scope.launch {
                        for (trigger in signal) {
                            drainAndFlushPendingInputs(machine, route)
                        }
                    }
                }
            }
        }
        signal.trySend(Unit)
    }

    private suspend fun drainAndFlushPendingInputs(
        machine: HostMachine,
        route: ResolvedSshRoute,
    ) {
        val queue = inputQueueFor(machine.id)
        while (scope.isActive) {
            val batch = ArrayList<PendingPaneInput>(16)
            while (true) {
                val next = queue.poll() ?: break
                batch.add(next)
            }
            if (batch.isEmpty()) break

            val maxSeqInBatch = batch.maxOf { it.seq }
            val targetPaneId = batch.last().paneId
            val coalescedRpcCommands = buildCoalescedInputCommands(batch)
            if (coalescedRpcCommands.isEmpty()) continue

            val prevAnsi = cachedInputs[machine.id]?.ansiByPane?.get(targetPaneId)
            val readCommand = RemoteShellCommand.ApiBridgeLine(buildPaneReadJsonLine(targetPaneId))

            runCatching {
                val combinedCommands = coalescedRpcCommands + readCommand
                val rawResponse = executeBatchOnLane(
                    machine = machine,
                    route = route,
                    lane = WorkerLane.Input,
                    commands = combinedCommands,
                )

                val immediateAnsi = extractPaneAnsiFromResponse(rawResponse, targetPaneId)

                // If more keystrokes arrived while this batch was on the wire, immediately loop to send them
                if (queue.isNotEmpty()) {
                    if (immediateAnsi != null && immediateAnsi != prevAnsi) {
                        updateFocusedPaneSurfaceFromInput(
                            machineId = machine.id,
                            paneId = targetPaneId,
                            paneAnsi = immediateAnsi,
                            confirmedSeq = maxSeqInBatch,
                        )
                    }
                    return@runCatching
                }

                if (immediateAnsi != null && immediateAnsi != prevAnsi) {
                    updateFocusedPaneSurfaceFromInput(
                        machineId = machine.id,
                        paneId = targetPaneId,
                        paneAnsi = immediateAnsi,
                        confirmedSeq = maxSeqInBatch,
                    )
                } else {
                    // Remote PTY discipline or TUI may need a few milliseconds to echo back to the PTY master.
                    // Keep the optimistic echo visible and perform one fast follow-up pane.read if still idle.
                    delay(PTY_ECHO_SETTLE_DELAY_MS)
                    if (queue.isEmpty()) {
                        val followUpOut = executeBatchOnLane(
                            machine = machine,
                            route = route,
                            lane = WorkerLane.Input,
                            commands = listOf(readCommand),
                        )
                        val settledAnsi = extractPaneAnsiFromResponse(followUpOut, targetPaneId) ?: immediateAnsi
                        if (settledAnsi != null) {
                            updateFocusedPaneSurfaceFromInput(
                                machineId = machine.id,
                                paneId = targetPaneId,
                                paneAnsi = settledAnsi,
                                confirmedSeq = maxSeqInBatch,
                            )
                        } else {
                            retireOptimisticEditsUpTo(machine.id, maxSeqInBatch)
                        }
                    }
                }

                // Trigger an asynchronous non-blocking snapshot refresh only on Enter or Ctrl chords
                if (batch.any { shouldTriggerTopologyRefresh(it.event) }) {
                    scope.launch {
                        runCatching { refreshSnapshotAndSurface(machine, route) }
                    }
                }
            }.onFailure { err ->
                sshDebugLog.record(
                    machineId = machine.id,
                    tag = "RealSSH",
                    message = "Failed to send input to $targetPaneId: ${err.message}",
                    isError = true,
                )
            }
        }
    }

    /**
     * Coalesces consecutive [PendingPaneInput] events targeting the same pane into minimal JSON-RPC lines:
     * - Consecutive `TextCommit` / `Paste` events merge into a single `pane.send_text`.
     * - Consecutive `Key` events merge into a single `pane.send_keys` array.
     * - Accumulated text immediately followed by `Key` events on the same pane merges into a single `pane.send_input`.
     */
    internal fun buildCoalescedInputCommands(batch: List<PendingPaneInput>): List<RemoteShellCommand.ApiBridgeLine> {
        if (batch.isEmpty()) return emptyList()
        val result = ArrayList<RemoteShellCommand.ApiBridgeLine>(batch.size)

        var currentPaneId: String? = null
        val textBuffer = StringBuilder()
        val keyBuffer = ArrayList<String>(4)

        fun flushCurrent() {
            val paneId = currentPaneId ?: return
            val hasText = textBuffer.isNotEmpty()
            val hasKeys = keyBuffer.isNotEmpty()
            if (!hasText && !hasKeys) return

            val line = when {
                hasText && hasKeys -> {
                    val text = textBuffer.toString()
                    val keys = keyBuffer.toList()
                    buildJsonObject {
                        put("id", "in_${revisionCounter.incrementAndGet()}")
                        put("method", "pane.send_input")
                        put(
                            "params",
                            buildJsonObject {
                                put("pane_id", paneId)
                                put("text", text)
                                put(
                                    "keys",
                                    buildJsonArray {
                                        keys.forEach { add(JsonPrimitive(it)) }
                                    },
                                )
                            },
                        )
                    }.toString()
                }

                hasText -> {
                    val text = textBuffer.toString()
                    buildJsonObject {
                        put("id", "in_${revisionCounter.incrementAndGet()}")
                        put("method", "pane.send_text")
                        put(
                            "params",
                            buildJsonObject {
                                put("pane_id", paneId)
                                put("text", text)
                            },
                        )
                    }.toString()
                }

                else -> {
                    val keys = keyBuffer.toList()
                    buildJsonObject {
                        put("id", "key_${revisionCounter.incrementAndGet()}")
                        put("method", "pane.send_keys")
                        put(
                            "params",
                            buildJsonObject {
                                put("pane_id", paneId)
                                put(
                                    "keys",
                                    buildJsonArray {
                                        keys.forEach { add(JsonPrimitive(it)) }
                                    },
                                )
                            },
                        )
                    }.toString()
                }
            }
            result.add(RemoteShellCommand.ApiBridgeLine(line))
            textBuffer.setLength(0)
            keyBuffer.clear()
        }

        for (item in batch) {
            if (currentPaneId != null && currentPaneId != item.paneId) {
                flushCurrent()
            }
            currentPaneId = item.paneId

            when (val ev = item.event) {
                is ClientPaneInputEvent.TextCommit -> {
                    if (keyBuffer.isNotEmpty()) {
                        flushCurrent()
                        currentPaneId = item.paneId
                    }
                    textBuffer.append(ev.text)
                }

                is ClientPaneInputEvent.Paste -> {
                    if (keyBuffer.isNotEmpty()) {
                        flushCurrent()
                        currentPaneId = item.paneId
                    }
                    textBuffer.append(ev.text)
                }

                is ClientPaneInputEvent.Key -> {
                    keyBuffer.add(mapKeyEventToHerdrKeySpec(ev.event))
                }

                is ClientPaneInputEvent.Mouse -> Unit
            }
        }
        flushCurrent()
        return result
    }

    private fun toOptimisticEdit(
        seq: Long,
        paneId: String,
        event: ClientPaneInputEvent,
    ): OptimisticEdit? = when (event) {
        is ClientPaneInputEvent.TextCommit -> {
            if (event.text.isEmpty()) null else OptimisticEdit.InsertText(seq = seq, paneId = paneId, text = event.text)
        }

        is ClientPaneInputEvent.Paste -> {
            if (event.text.isEmpty()) null else OptimisticEdit.InsertText(seq = seq, paneId = paneId, text = event.text)
        }

        is ClientPaneInputEvent.Key -> {
            val ev = event.event
            if (ev.ctrl || ev.alt) {
                null
            } else {
                when (ev.code) {
                    ClientKeyCode.Backspace -> OptimisticEdit.Backspace(seq = seq, paneId = paneId)
                    ClientKeyCode.Enter -> OptimisticEdit.Newline(seq = seq, paneId = paneId)
                    ClientKeyCode.CharC -> OptimisticEdit.InsertText(seq = seq, paneId = paneId, text = if (ev.shift) "C" else "c")
                    ClientKeyCode.CharB -> OptimisticEdit.InsertText(seq = seq, paneId = paneId, text = if (ev.shift) "B" else "b")
                    ClientKeyCode.CharD -> OptimisticEdit.InsertText(seq = seq, paneId = paneId, text = if (ev.shift) "D" else "d")
                    ClientKeyCode.CharZ -> OptimisticEdit.InsertText(seq = seq, paneId = paneId, text = if (ev.shift) "Z" else "z")
                    ClientKeyCode.CharY -> OptimisticEdit.InsertText(seq = seq, paneId = paneId, text = if (ev.shift) "Y" else "y")
                    ClientKeyCode.CharN -> OptimisticEdit.InsertText(seq = seq, paneId = paneId, text = if (ev.shift) "N" else "n")
                    else -> null
                }
            }
        }

        is ClientPaneInputEvent.Mouse -> null
    }

    private fun isControlInterruptEvent(event: ClientPaneInputEvent): Boolean =
        event is ClientPaneInputEvent.Key && (event.event.ctrl || event.event.code == ClientKeyCode.Escape)

    private fun shouldTriggerTopologyRefresh(event: ClientPaneInputEvent): Boolean =
        when (event) {
            is ClientPaneInputEvent.Key -> event.event.code == ClientKeyCode.Enter || event.event.ctrl
            is ClientPaneInputEvent.TextCommit -> event.text.contains('\n') || event.text.contains('\r')
            is ClientPaneInputEvent.Paste -> event.text.contains('\n') || event.text.contains('\r')
            is ClientPaneInputEvent.Mouse -> false
        }

    private fun retireOptimisticEditsUpTo(machineId: String, confirmedSeq: Long) {
        if (confirmedSeq <= 0L) return
        lastInputConfirmedSeq.compute(machineId) { _, prev ->
            if (prev == null || confirmedSeq > prev) confirmedSeq else prev
        }
        val edits = optimisticEdits[machineId] ?: return
        synchronized(edits) {
            while (edits.isNotEmpty() && edits.first().seq <= confirmedSeq) {
                edits.removeFirst()
            }
        }
    }

    /**
     * Queues `pane.scroll` for [paneId] to [offsetFromBottom] and shows the new position at once.
     * One worker for each machine sends only the newest position, then reads that pane.
     */
    private fun requestPaneScroll(
        machine: HostMachine,
        route: ResolvedSshRoute,
        paneId: String,
        offsetFromBottom: Int,
    ) {
        val maxOffset = paneScrolls[machine.id]?.get(paneId)?.maxScrollback?.toInt() ?: 0
        val target = if (maxOffset > 0) offsetFromBottom.coerceIn(0, maxOffset) else offsetFromBottom.coerceAtLeast(0)
        pendingPaneScrolls[machine.id] = PendingPaneScroll(paneId, target)
        setLocalPaneScrollOffset(machine.id, paneId, target)
        republishCachedSurface(machine.id)

        val signal = paneScrollSignals.computeIfAbsent(machine.id) { Channel(Channel.CONFLATED) }
        paneScrollJobs.compute(machine.id) { _, job ->
            job?.takeIf { it.isActive } ?: scope.launch {
                for (trigger in signal) {
                    while (isActive) {
                        val next = pendingPaneScrolls.remove(machine.id) ?: break
                        sendPaneScroll(machine, route, next)
                    }
                }
            }
        }
        signal.trySend(Unit)
    }

    private suspend fun sendPaneScroll(
        machine: HostMachine,
        route: ResolvedSshRoute,
        scroll: PendingPaneScroll,
    ) {
        val scrollLine = HerdrRpcBuilder.toNdjsonLine(
            HerdrRpcBuilder.paneScroll(paneId = scroll.paneId, offsetFromBottom = scroll.offsetFromBottom),
        ).trim()
        runCatching {
            val out = executeBatchOnLane(
                machine = machine,
                route = route,
                lane = WorkerLane.Input,
                commands = listOf(
                    RemoteShellCommand.ApiBridgeLine(scrollLine),
                    RemoteShellCommand.ApiBridgeLine(buildPaneReadJsonLine(scroll.paneId)),
                ),
            )
            // A newer request replaces this one. Keep the newer position on the screen.
            if (pendingPaneScrolls[machine.id] == null) {
                setLocalPaneScrollOffset(machine.id, scroll.paneId, scroll.offsetFromBottom)
            }
            val paneAnsi = extractPaneAnsiFromResponse(out, scroll.paneId)
            if (paneAnsi != null) {
                updateFocusedPaneSurfaceFromInput(
                    machineId = machine.id,
                    paneId = scroll.paneId,
                    paneAnsi = paneAnsi,
                    confirmedSeq = 0L,
                )
            }
        }.onFailure { err ->
            sshDebugLog.record(
                machineId = machine.id,
                tag = "RealSSH",
                message = "pane.scroll for ${scroll.paneId} failed: ${err.message}",
                isError = true,
            )
        }
    }

    private fun setLocalPaneScrollOffset(machineId: String, paneId: String, offsetFromBottom: Int) {
        paneScrolls.compute(machineId) { _, existing ->
            val current = existing.orEmpty()
            val previous = current[paneId] ?: PaneSurfaceScroll()
            current + (paneId to previous.copy(offsetFromBottom = offsetFromBottom.coerceAtLeast(0).toUInt()))
        }
    }

    private fun republishCachedSurface(machineId: String) {
        val cached = cachedInputs[machineId] ?: return
        paneSurfaceBus.publish(
            machineId,
            buildCompositeSurfaceFrame(
                machineId = machineId,
                snapshot = cached.snapshot,
                layout = cached.layout,
                ansiByPane = cached.ansiByPane,
            ),
        )
    }

    /** Reads `panes[].scroll` (`offset_from_bottom`, `max_offset_from_bottom`, `viewport_rows`) from a snapshot. */
    private fun parsePaneScrolls(snapshotObj: JsonObject): Map<String, PaneSurfaceScroll> {
        val panesArray = snapshotObj["panes"] as? JsonArray ?: return emptyMap()
        return panesArray.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = (obj["pane_id"] ?: obj["id"])?.jsonPrimitive?.content ?: return@mapNotNull null
            val scrollObj = obj["scroll"] as? JsonObject ?: return@mapNotNull null
            fun field(name: String): Long =
                scrollObj[name]?.jsonPrimitive?.longOrNull?.coerceIn(0L, UInt.MAX_VALUE.toLong()) ?: 0L
            id to PaneSurfaceScroll(
                offsetFromBottom = field("offset_from_bottom").toUInt(),
                maxScrollback = field("max_offset_from_bottom").toUInt(),
                viewportRows = field("viewport_rows").coerceAtMost(UShort.MAX_VALUE.toLong()).toUShort(),
            )
        }.toMap()
    }

    private fun updateFocusedPaneSurfaceFromInput(
        machineId: String,
        paneId: String,
        paneAnsi: String,
        confirmedSeq: Long,
    ) {
        if (confirmedSeq > 0L) {
            lastInputConfirmedSeq.compute(machineId) { _, prev ->
                if (prev == null || confirmedSeq > prev) confirmedSeq else prev
            }
            retireOptimisticEditsUpTo(machineId, confirmedSeq)
        }

        val updatedCache = cachedInputs.compute(machineId) { _, existing ->
            if (existing == null) {
                null
            } else {
                existing.copy(ansiByPane = existing.ansiByPane + (paneId to paneAnsi))
            }
        } ?: return

        val frame = buildCompositeSurfaceFrame(
            machineId = machineId,
            snapshot = updatedCache.snapshot,
            layout = updatedCache.layout,
            ansiByPane = updatedCache.ansiByPane,
        )
        paneSurfaceBus.publish(machineId, frame)
    }

    private fun applyOptimisticPaneFocus(machineId: String, paneId: String) {
        val currentSnapshot = endpointSnapshotBus.snapshotFor(machineId)
        if (currentSnapshot != null && currentSnapshot.focusedPaneId != paneId) {
            endpointSnapshotBus.publish(
                machineId,
                currentSnapshot.copy(
                    revision = revisionCounter.incrementAndGet().toULong(),
                    focusedPaneId = paneId,
                    panes = currentSnapshot.panes.map { it.copy(focused = it.paneId == paneId) },
                    agents = currentSnapshot.agents.map { it.copy(focused = it.paneId == paneId) },
                ),
            )
        }
        val currentSurface = paneSurfaceBus.surfaceFor(machineId)
        if (currentSurface != null) {
            paneSurfaceBus.publish(
                machineId,
                currentSurface.copy(
                    revision = revisionCounter.incrementAndGet().toULong(),
                    panes = currentSurface.panes.map { it.copy(focused = it.paneId == paneId) },
                ),
            )
        }
    }

    internal fun applyOptimisticEditToFrame(
        frame: PaneSurfaceFrame,
        edit: OptimisticEdit,
    ): PaneSurfaceFrame {
        val gridCols = frame.frame.width.toInt()
        val gridRows = frame.frame.height.toInt()
        if (gridCols <= 0 || gridRows <= 0 || frame.frame.cells.size < gridCols * gridRows) {
            return frame
        }

        val targetPane = frame.panes.firstOrNull { it.paneId == edit.paneId }
            ?: frame.panes.firstOrNull { it.focused }
            ?: frame.panes.firstOrNull()
        val minCol = (targetPane?.innerRect?.x?.toInt() ?: 0).coerceIn(0, gridCols - 1)
        val minRow = (targetPane?.innerRect?.y?.toInt() ?: 0).coerceIn(0, gridRows - 1)
        val maxCol = if (targetPane != null) {
            (minCol + targetPane.innerRect.width.toInt() - 1).coerceIn(minCol, gridCols - 1)
        } else {
            gridCols - 1
        }
        val maxRow = if (targetPane != null) {
            (minRow + targetPane.innerRect.height.toInt() - 1).coerceIn(minRow, gridRows - 1)
        } else {
            gridRows - 1
        }

        val cur = frame.frame.cursor
        var cursorCol = (cur?.x?.toInt() ?: minCol).coerceIn(minCol, maxCol)
        var cursorRow = (cur?.y?.toInt() ?: minRow).coerceIn(minRow, maxRow)
        val cells = frame.frame.cells.toMutableList()

        fun clearPredictedEchoOnRow(row: Int) {
            for (c in minCol..maxCol) {
                val idx = row * gridCols + c
                val existing = cells[idx]
                if (existing.isPredictedEcho) {
                    val cleanMod = (existing.modifier.toInt() and CellData.MOD_PREDICTED_ECHO.toInt().inv()).toUShort()
                    cells[idx] = existing.copy(modifier = cleanMod)
                }
            }
        }

        when (edit) {
            is OptimisticEdit.InsertText -> {
                for (ch in edit.text) {
                    when {
                        ch == '\n' || ch == '\r' -> {
                            clearPredictedEchoOnRow(cursorRow)
                            cursorCol = minCol
                            cursorRow = (cursorRow + 1).coerceAtMost(maxRow)
                        }

                        ch == '\b' || ch == '\u007f' -> {
                            if (cursorCol > minCol) {
                                cursorCol--
                                cells[cursorRow * gridCols + cursorCol] = BLANK_CELL
                            }
                        }

                        !ch.isISOControl() -> {
                            val idx = cursorRow * gridCols + cursorCol
                            val existing = cells[idx]
                            cells[idx] = CellData(
                                symbol = codepointToSymbol(ch.code),
                                fg = existing.fg,
                                bg = existing.bg,
                                modifier = CellData.MOD_PREDICTED_ECHO,
                            )
                            if (cursorCol < maxCol) {
                                cursorCol++
                            } else if (cursorRow < maxRow) {
                                cursorCol = minCol
                                cursorRow++
                            }
                        }
                    }
                }
            }

            is OptimisticEdit.Backspace -> {
                if (cursorCol > minCol) {
                    cursorCol--
                    cells[cursorRow * gridCols + cursorCol] = BLANK_CELL
                }
            }

            is OptimisticEdit.Newline -> {
                clearPredictedEchoOnRow(cursorRow)
                cursorCol = minCol
                cursorRow = (cursorRow + 1).coerceAtMost(maxRow)
            }
        }

        return frame.copy(
            revision = revisionCounter.incrementAndGet().toULong(),
            frame = frame.frame.copy(
                cells = cells,
                cursor = CursorState(
                    x = cursorCol.toUShort(),
                    y = cursorRow.toUShort(),
                    shape = cur?.shape ?: CursorShape.Block,
                    visible = true,
                ),
            ),
        )
    }

    private fun buildPaneReadJsonLine(paneId: String): String =
        buildJsonObject {
            put("id", "read_$paneId")
            put("method", "pane.read")
            put(
                "params",
                buildJsonObject {
                    put("pane_id", paneId)
                    put("source", "visible")
                    put("format", "ansi")
                },
            )
        }.toString()

    private fun extractPaneAnsiFromResponse(rawResponse: String, targetPaneId: String): String? {
        var found: String? = null
        rawResponse.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") && it.endsWith("}") }
            .forEach { line ->
                runCatching {
                    val obj = json.parseToJsonElement(line).jsonObject
                    val readObj = obj["result"]?.jsonObject?.get("read")?.jsonObject ?: return@forEach
                    val pId = readObj["pane_id"]?.jsonPrimitive?.content
                    val text = readObj["text"]?.jsonPrimitive?.content
                    if (pId == targetPaneId && text != null) {
                        found = text
                    }
                }
            }
        return found
    }

    private fun parsePaneReadLinesInto(
        rawResponse: String,
        targetMap: MutableMap<String, String>,
    ) {
        rawResponse.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") && it.endsWith("}") }
            .forEach { line ->
                runCatching {
                    val obj = json.parseToJsonElement(line).jsonObject
                    val readObj = obj["result"]?.jsonObject?.get("read")?.jsonObject
                    val pId = readObj?.get("pane_id")?.jsonPrimitive?.content
                    val text = readObj?.get("text")?.jsonPrimitive?.content
                    if (!pId.isNullOrBlank() && text != null) {
                        targetMap[pId] = text
                    }
                }
            }
    }

    private fun mapKeyEventToHerdrKeySpec(ev: ClientKeyEvent): String {
        val base = when (ev.code) {
            ClientKeyCode.Enter -> "Enter"
            ClientKeyCode.Escape -> "Esc"
            ClientKeyCode.Tab -> "Tab"
            ClientKeyCode.Backspace -> "Backspace"
            ClientKeyCode.Up -> "Up"
            ClientKeyCode.Down -> "Down"
            ClientKeyCode.Left -> "Left"
            ClientKeyCode.Right -> "Right"
            ClientKeyCode.Home -> "Home"
            ClientKeyCode.End -> "End"
            ClientKeyCode.PageUp -> "PageUp"
            ClientKeyCode.PageDown -> "PageDown"
            ClientKeyCode.Delete -> "Delete"
            ClientKeyCode.CharC -> "c"
            ClientKeyCode.CharB -> "b"
            ClientKeyCode.CharD -> "d"
            ClientKeyCode.CharZ -> "z"
            ClientKeyCode.CharY -> "y"
            ClientKeyCode.CharN -> "n"
        }
        return buildString {
            if (ev.ctrl) append("ctrl+")
            if (ev.alt) append("alt+")
            if (ev.shift && base.length > 1) append("shift+")
            append(base)
        }
    }

    private suspend fun getOrOpenSession(
        machine: HostMachine,
        route: ResolvedSshRoute,
    ): Session {
        val fastExisting = sessions[machine.id]
        if (fastExisting != null && fastExisting.isConnected) {
            return fastExisting
        }
        return sessionMutexFor(machine.id).withLock {
            val existing = sessions[machine.id]
            if (existing != null && existing.isConnected) {
                return@withLock existing
            }
            inputWorkers.remove(machine.id)?.close()
            pollWorkers.remove(machine.id)?.close()
            JschAndroidCrypto.installGlobalDefaults()
            val jsch = JSch()
            val preferredAuths = when (val auth = machine.authMethod) {
                is SshAuthMethod.Password -> "password,keyboard-interactive,publickey"
                is SshAuthMethod.KeystoreKey -> {
                    jsch.addIdentity(
                        AndroidKeystoreJschIdentity(
                            keystoreKeyManager = keystoreKeyManagerProvider(),
                            keyAlias = auth.keyAlias,
                            algorithm = auth.algorithm,
                            requireBiometric = auth.requireBiometric,
                        ),
                        null,
                    )
                    "publickey,password,keyboard-interactive"
                }
                is SshAuthMethod.HardwareSecurityKey -> "publickey,keyboard-interactive,password"
            }
            val session = jsch.getSession(
                route.entryUsername.ifBlank { machine.username },
                route.entryHost,
                route.entryPort,
            )
            if (machine.authMethod is SshAuthMethod.Password) {
                val passwordAuth = machine.authMethod as SshAuthMethod.Password
                session.setPassword(passwordAuth.encryptedPasswordRef.encodeToByteArray())
            }
            val props = Properties().apply {
                put("StrictHostKeyChecking", "no")
                put("PreferredAuthentications", preferredAuths)
                put("Compression", "none")
            }
            session.setConfig(props)
            session.setSocketFactory(LowLatencySocketFactory)
            runCatching {
                session.setServerAliveInterval(10_000)
                session.setServerAliveCountMax(3)
            }
            JschAndroidCrypto.configureSession(session)
            session.connect(SOCKET_CONNECT_TIMEOUT_MS)
            sessions[machine.id] = session
            session
        }
    }

    private fun workerMapFor(lane: WorkerLane): ConcurrentHashMap<String, PersistentShellWorker> =
        when (lane) {
            WorkerLane.Input -> inputWorkers
            WorkerLane.Poll -> pollWorkers
        }

    private fun getOrOpenWorkerLocked(
        machineId: String,
        lane: WorkerLane,
        session: Session,
    ): PersistentShellWorker {
        val map = workerMapFor(lane)
        val existing = map[machineId]
        if (existing != null && existing.isAlive) {
            return existing
        }
        existing?.close()
        val channel = session.openChannel("exec") as ChannelExec
        val initCmd = "export PATH=\"\$HOME/.local/bin:\$HOME/.cargo/bin:/usr/local/bin:\$PATH\"; exec sh"
        channel.setCommand(initCmd)
        val rawIn = channel.inputStream
        val rawOut = channel.outputStream
        channel.setErrStream(ByteArrayOutputStream())
        channel.connect(CHANNEL_CONNECT_TIMEOUT_MS)
        val worker = PersistentShellWorker(
            channel = channel,
            stdin = rawOut,
            stdout = BufferedReader(InputStreamReader(rawIn, Charsets.UTF_8), 32 * 1024),
        )
        map[machineId] = worker
        return worker
    }

    private suspend fun executeBatchOnLane(
        machine: HostMachine,
        route: ResolvedSshRoute,
        lane: WorkerLane,
        commands: List<RemoteShellCommand>,
    ): String {
        if (commands.isEmpty()) return ""
        val customExecutor = remoteBatchExecutor
        if (customExecutor != null) {
            return customExecutor(machine, route, lane, commands)
        }
        return laneMutexFor(machine.id, lane).withLock {
            val session = getOrOpenSession(machine, route)
            runCatching {
                val worker = getOrOpenWorkerLocked(machine.id, lane, session)
                runCommandsOnWorker(worker, commands)
            }.recoverCatching {
                // If the persistent shell channel dropped, discard it and fall back / reopen cleanly
                workerMapFor(lane).remove(machine.id)?.close()
                val freshSession = getOrOpenSession(machine, route)
                runCatching {
                    val freshWorker = getOrOpenWorkerLocked(machine.id, lane, freshSession)
                    runCommandsOnWorker(freshWorker, commands)
                }.getOrElse {
                    execBatchOneShot(freshSession, commands)
                }
            }.getOrThrow()
        }
    }

    private fun runCommandsOnWorker(
        worker: PersistentShellWorker,
        commands: List<RemoteShellCommand>,
    ): String {
        val batchId = shellBatchCounter.incrementAndGet()
        val (script, sentinel) = buildShellBatchScript(batchId, commands)
        worker.stdin.write(script.toByteArray(Charsets.UTF_8))
        worker.stdin.flush()

        val out = StringBuilder(256)
        while (true) {
            val line = worker.stdout.readLine()
                ?: throw IOException("Persistent SSH shell stream closed before sentinel")
            val trimmed = line.trim()
            if (trimmed == sentinel) {
                break
            }
            if (trimmed.endsWith(sentinel)) {
                val prefix = line.substringBeforeLast(sentinel)
                if (prefix.isNotEmpty()) {
                    out.append(prefix).append('\n')
                }
                break
            }
            out.append(line).append('\n')
        }
        return out.toString()
    }

    private fun execBatchOneShot(
        session: Session,
        commands: List<RemoteShellCommand>,
    ): String {
        val batchId = shellBatchCounter.incrementAndGet()
        val body = buildString(commands.size * 160 + 96) {
            append("export PATH=\"\$HOME/.local/bin:\$HOME/.cargo/bin:/usr/local/bin:\$PATH\"\n")
            commands.forEachIndexed { index, cmd ->
                when (cmd) {
                    is RemoteShellCommand.Exec -> {
                        append(cmd.command.trim()).append('\n')
                    }
                    is RemoteShellCommand.ApiBridgeLine -> {
                        val eofMarker = "__BISON_EOF_${batchId}_${index}__"
                        append("herdr remote-api-bridge <<'")
                            .append(eofMarker)
                            .append("'\n")
                            .append(cmd.ndjsonLine.trim())
                            .append('\n')
                            .append(eofMarker)
                            .append('\n')
                    }
                }
            }
        }
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(body)
        val stdout = channel.inputStream
        channel.setErrStream(ByteArrayOutputStream())
        channel.connect(CHANNEL_CONNECT_TIMEOUT_MS)
        return try {
            stdout.readBytes().toString(Charsets.UTF_8)
        } finally {
            runCatching { channel.disconnect() }
        }
    }

    internal fun buildShellBatchScript(
        batchId: Long,
        commands: List<RemoteShellCommand>,
    ): Pair<String, String> {
        val sentinel = "__BISON_DONE_${batchId}__"
        val script = buildString(commands.size * 160 + 64) {
            commands.forEachIndexed { index, cmd ->
                when (cmd) {
                    is RemoteShellCommand.Exec -> {
                        append(cmd.command.trim())
                        append('\n')
                    }

                    is RemoteShellCommand.ApiBridgeLine -> {
                        val eofMarker = "__BISON_EOF_${batchId}_${index}__"
                        append("herdr remote-api-bridge <<'")
                        append(eofMarker)
                        append("'\n")
                        append(cmd.ndjsonLine.trim())
                        append('\n')
                        append(eofMarker)
                        append('\n')
                    }
                }
            }
            append("printf '\\n")
            append(sentinel)
            append("\\n'\n")
        }
        return script to sentinel
    }

    private data class ParsedSnapshotBundle(
        val snapshot: ClientShellSnapshot,
        val layoutsByTab: Map<String, PaneLayoutSnapshot>,
    )

    private fun parseSnapshotObject(
        machineId: String,
        snapshotObj: JsonObject,
        sel: ClientSelectionState,
    ): ParsedSnapshotBundle {
        val serverFocusedWsId = snapshotObj["focused_workspace_id"]?.jsonPrimitive?.content
        val serverFocusedTabId = snapshotObj["focused_tab_id"]?.jsonPrimitive?.content
        val serverFocusedPaneId = snapshotObj["focused_pane_id"]?.jsonPrimitive?.content

        val workspacesArray = snapshotObj["workspaces"] as? JsonArray ?: JsonArray(emptyList())
        val tabsArray = snapshotObj["tabs"] as? JsonArray ?: JsonArray(emptyList())
        val panesArray = snapshotObj["panes"] as? JsonArray ?: JsonArray(emptyList())
        val agentsArray = snapshotObj["agents"] as? JsonArray ?: JsonArray(emptyList())
        val layoutsArray = snapshotObj["layouts"] as? JsonArray ?: JsonArray(emptyList())

        val rawWorkspaces = workspacesArray.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = (obj["workspace_id"] ?: obj["id"])?.jsonPrimitive?.content ?: return@mapNotNull null
            val activeTabId = obj["active_tab_id"]?.jsonPrimitive?.content
            val label = obj["label"]?.jsonPrimitive?.content ?: id
            val branch = obj["branch"]?.jsonPrimitive?.content
            val statusStr = (obj["agent_status"] ?: obj["state"])?.jsonPrimitive?.content
            ClientShellWorkspace(
                workspaceId = id,
                activeTabId = activeTabId,
                label = label,
                branch = branch,
                focused = obj["focused"]?.jsonPrimitive?.booleanOrNull ?: (id == serverFocusedWsId),
                agentStatus = parseAgentStatus(statusStr),
            )
        }

        val effectiveWsId = sel.focusedWorkspaceId?.takeIf { id -> rawWorkspaces.any { it.workspaceId == id } }
            ?: serverFocusedWsId
            ?: rawWorkspaces.firstOrNull()?.workspaceId
        sel.focusedWorkspaceId = effectiveWsId

        val rawTabs = tabsArray.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = (obj["tab_id"] ?: obj["id"])?.jsonPrimitive?.content ?: return@mapNotNull null
            val wsId = obj["workspace_id"]?.jsonPrimitive?.content ?: effectiveWsId.orEmpty()
            val number = obj["number"]?.jsonPrimitive?.intOrNull ?: 1
            val label = obj["label"]?.jsonPrimitive?.content ?: id
            val statusStr = (obj["agent_status"] ?: obj["state"])?.jsonPrimitive?.content
            ClientShellTab(
                tabId = id,
                workspaceId = wsId,
                number = number,
                label = label,
                focused = obj["focused"]?.jsonPrimitive?.booleanOrNull ?: (id == serverFocusedTabId),
                agentStatus = parseAgentStatus(statusStr),
            )
        }

        val wsTabs = rawTabs.filter { it.workspaceId == effectiveWsId }
        val activeWorkspaceObj = rawWorkspaces.firstOrNull { it.workspaceId == effectiveWsId }
        val effectiveTabId = sel.focusedTabId?.takeIf { id -> wsTabs.any { it.tabId == id } }
            ?: activeWorkspaceObj?.activeTabId?.takeIf { id -> wsTabs.any { it.tabId == id } }
            ?: serverFocusedTabId?.takeIf { id -> wsTabs.any { it.tabId == id } }
            ?: wsTabs.firstOrNull()?.tabId
            ?: rawTabs.firstOrNull()?.tabId
        sel.focusedTabId = effectiveTabId

        val rawPanes = panesArray.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = (obj["pane_id"] ?: obj["id"])?.jsonPrimitive?.content ?: return@mapNotNull null
            val wsId = obj["workspace_id"]?.jsonPrimitive?.content ?: effectiveWsId.orEmpty()
            val tId = obj["tab_id"]?.jsonPrimitive?.content ?: effectiveTabId.orEmpty()
            val label = obj["label"]?.jsonPrimitive?.content ?: id
            val cwd = obj["cwd"]?.jsonPrimitive?.content.orEmpty()
            ClientShellPane(
                paneId = id,
                workspaceId = wsId,
                tabId = tId,
                label = label,
                cwd = cwd,
                focused = obj["focused"]?.jsonPrimitive?.booleanOrNull ?: (id == serverFocusedPaneId),
            )
        }

        val tabPanes = rawPanes.filter { it.tabId == effectiveTabId }
        val effectivePaneId = sel.focusedPaneId?.takeIf { id -> tabPanes.any { it.paneId == id } }
            ?: serverFocusedPaneId?.takeIf { id -> tabPanes.any { it.paneId == id } }
            ?: tabPanes.firstOrNull()?.paneId
            ?: rawPanes.firstOrNull()?.paneId
        sel.focusedPaneId = effectivePaneId

        val agents = agentsArray.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val pId = obj["pane_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val wsId = obj["workspace_id"]?.jsonPrimitive?.content.orEmpty()
            val tId = obj["tab_id"]?.jsonPrimitive?.content.orEmpty()
            val name = obj["name"]?.jsonPrimitive?.content
            val agent = obj["agent"]?.jsonPrimitive?.content.orEmpty()
            val displayAgent = obj["display_agent"]?.jsonPrimitive?.content ?: name ?: agent
            val title = obj["title"]?.jsonPrimitive?.content
            val terminalTitle = obj["terminal_title"]?.jsonPrimitive?.content
            val terminalTitleStripped = obj["terminal_title_stripped"]?.jsonPrimitive?.content
            val statusStr = (obj["agent_status"] ?: obj["state"])?.jsonPrimitive?.content
            ClientShellAgent(
                paneId = pId,
                workspaceId = wsId,
                tabId = tId,
                name = name,
                displayAgent = displayAgent,
                agent = agent,
                title = title,
                terminalTitle = terminalTitle,
                terminalTitleStripped = terminalTitleStripped,
                agentStatus = parseAgentStatus(statusStr),
                focused = pId == effectivePaneId,
            )
        }

        // Propagate agent statuses up to workspaces and tabs so top bar pills show attention dots
        val updatedPanes = rawPanes.map { p ->
            p.copy(focused = p.paneId == effectivePaneId)
        }
        val updatedTabs = rawTabs.map { t ->
            val topTabAgentStatus = agents
                .filter { it.tabId == t.tabId }
                .minByOrNull { it.agentStatus.priorityOrder }
                ?.agentStatus
            t.copy(
                focused = t.tabId == effectiveTabId,
                agentStatus = topTabAgentStatus?.takeIf { it != AgentStatus.Unknown } ?: t.agentStatus,
            )
        }
        val updatedWorkspaces = rawWorkspaces.map { ws ->
            val topWsAgentStatus = agents
                .filter { it.workspaceId == ws.workspaceId }
                .minByOrNull { it.agentStatus.priorityOrder }
                ?.agentStatus
            ws.copy(
                focused = ws.workspaceId == effectiveWsId,
                agentStatus = topWsAgentStatus?.takeIf { it != AgentStatus.Unknown } ?: ws.agentStatus,
            )
        }

        val layoutsByTab = mutableMapOf<String, PaneLayoutSnapshot>()
        layoutsArray.forEach { el ->
            val obj = el as? JsonObject ?: return@forEach
            val tabId = obj["tab_id"]?.jsonPrimitive?.content ?: return@forEach
            val wsId = obj["workspace_id"]?.jsonPrimitive?.content ?: effectiveWsId.orEmpty()
            val zoomed = obj["zoomed"]?.jsonPrimitive?.booleanOrNull ?: false
            val lPanes = (obj["panes"] as? JsonArray)?.mapNotNull { pEl ->
                val pObj = pEl as? JsonObject ?: return@mapNotNull null
                val pId = pObj["pane_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val rectObj = pObj["rect"] as? JsonObject ?: return@mapNotNull null
                PaneLayoutPane(
                    paneId = pId,
                    rect = parseSurfaceRect(rectObj),
                    focused = pId == effectivePaneId,
                )
            }.orEmpty()
            val lSplits = (obj["splits"] as? JsonArray)?.mapNotNull { sEl ->
                val sObj = sEl as? JsonObject ?: return@mapNotNull null
                val sId = sObj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val dirStr = sObj["direction"]?.jsonPrimitive?.content ?: "vertical"
                val ratio = sObj["ratio"]?.jsonPrimitive?.floatOrNull ?: 0.5f
                val rectObj = sObj["rect"] as? JsonObject ?: return@mapNotNull null
                PaneLayoutSplit(
                    id = sId,
                    direction = if (dirStr.equals("horizontal", ignoreCase = true)) {
                        PaneSurfaceSplitDirection.Horizontal
                    } else {
                        PaneSurfaceSplitDirection.Vertical
                    },
                    ratio = ratio,
                    rect = parseSurfaceRect(rectObj),
                )
            }.orEmpty()
            layoutsByTab[tabId] = PaneLayoutSnapshot(
                workspaceId = wsId,
                tabId = tabId,
                zoomed = zoomed,
                panes = lPanes,
                splits = lSplits,
            )
        }

        val snapshot = ClientShellSnapshot(
            bootId = "herdr-$machineId",
            revision = revisionCounter.incrementAndGet().toULong(),
            focusedWorkspaceId = effectiveWsId,
            focusedTabId = effectiveTabId,
            focusedPaneId = effectivePaneId,
            workspaces = updatedWorkspaces,
            tabs = updatedTabs,
            panes = updatedPanes,
            agents = agents,
        )
        return ParsedSnapshotBundle(snapshot = snapshot, layoutsByTab = layoutsByTab)
    }

    private fun buildCompositeSurfaceFrame(
        machineId: String,
        snapshot: ClientShellSnapshot,
        layout: PaneLayoutSnapshot?,
        ansiByPane: Map<String, String>,
    ): PaneSurfaceFrame {
        val rawLayoutPanes = layout?.panes?.takeIf { it.isNotEmpty() } ?: run {
            val fallbackPaneId = snapshot.focusedPaneId ?: snapshot.panes.firstOrNull()?.paneId ?: "w1:p1"
            listOf(
                PaneLayoutPane(
                    paneId = fallbackPaneId,
                    rect = SurfaceRect(0u, 0u, 120u, 40u),
                    focused = true,
                ),
            )
        }

        val rawTotalCols = rawLayoutPanes.maxOf { (it.rect.x + it.rect.width).toInt() }.coerceIn(20, 300)
        val rawTotalRows = rawLayoutPanes.maxOf { (it.rect.y + it.rect.height).toInt() }.coerceIn(8, 150)
        val customViewport = viewportSizes[machineId]
        val totalCols = customViewport?.first ?: rawTotalCols
        val totalRows = customViewport?.second ?: rawTotalRows

        fun scaleRect(r: SurfaceRect): SurfaceRect {
            if (totalCols == rawTotalCols && totalRows == rawTotalRows) return r
            val sx = ((r.x.toInt() * totalCols) / rawTotalCols).coerceIn(0, totalCols - 1)
            val sy = ((r.y.toInt() * totalRows) / rawTotalRows).coerceIn(0, totalRows - 1)
            val ex = (((r.x.toInt() + r.width.toInt()) * totalCols) / rawTotalCols).coerceIn(sx + 1, totalCols)
            val ey = (((r.y.toInt() + r.height.toInt()) * totalRows) / rawTotalRows).coerceIn(sy + 1, totalRows)
            return SurfaceRect(
                x = sx.toUShort(),
                y = sy.toUShort(),
                width = (ex - sx).toUShort(),
                height = (ey - sy).toUShort(),
            )
        }

        val layoutPanes = rawLayoutPanes.map { lp ->
            lp.copy(rect = scaleRect(lp.rect))
        }
        val scaledLayout = layout?.copy(
            panes = layoutPanes,
            splits = layout.splits.map { sp -> sp.copy(rect = scaleRect(sp.rect)) },
        )

        val grid = Array(totalCols * totalRows) { BLANK_CELL }

        var focusedCursor: CursorState? = null
        val surfacePanes = layoutPanes.map { lp ->
            val paneMeta = snapshot.panes.firstOrNull { it.paneId == lp.paneId }
            val agentMeta = snapshot.agents.firstOrNull { it.paneId == lp.paneId }
            val ansiText = ansiByPane[lp.paneId].orEmpty()
            val isFocused = lp.paneId == snapshot.focusedPaneId || lp.focused

            val cursorPos = renderAnsiIntoRect(
                ansiText = ansiText,
                rect = lp.rect,
                grid = grid,
                gridCols = totalCols,
                gridRows = totalRows,
            )
            if (isFocused) {
                focusedCursor = CursorState(
                    x = cursorPos.first.toUShort(),
                    y = cursorPos.second.toUShort(),
                    shape = CursorShape.Block,
                    visible = true,
                )
            }

            PaneSurfacePane(
                paneId = lp.paneId,
                rect = lp.rect,
                innerRect = lp.rect,
                focused = isFocused,
                zoomed = scaledLayout?.zoomed == true,
                title = agentMeta?.displayAgent?.takeIf { it.isNotBlank() }
                    ?: paneMeta?.label
                    ?: lp.paneId,
                agentStatus = agentMeta?.agentStatus ?: AgentStatus.Unknown,
                scroll = paneScrolls[machineId]?.get(lp.paneId)?.copy(viewportRows = lp.rect.height)
                    ?: PaneSurfaceScroll(viewportRows = lp.rect.height),
            )
        }

        val splits = if (scaledLayout != null) deriveSplitsFromLayout(scaledLayout) else emptyList()

        val baseFrame = PaneSurfaceFrame(
            bootId = snapshot.bootId,
            revision = revisionCounter.incrementAndGet().toULong(),
            frame = FrameData(
                width = totalCols.toUShort(),
                height = totalRows.toUShort(),
                cells = grid.toList(),
                cursor = focusedCursor,
            ),
            panes = surfacePanes,
            splits = splits,
        )

        // Overlay any unconfirmed optimistic local echo edits (<1.5s TTL) so polls/resizes never drop in-flight keys
        val editsDeque = optimisticEdits[machineId] ?: return baseFrame
        val activeEdits = synchronized(editsDeque) {
            val now = System.currentTimeMillis()
            while (editsDeque.isNotEmpty() && now - editsDeque.first().createdAtMs > OPTIMISTIC_ECHO_TTL_MS) {
                editsDeque.removeFirst()
            }
            if (editsDeque.isEmpty()) emptyList() else editsDeque.toList()
        }
        return activeEdits.fold(baseFrame) { acc, edit ->
            applyOptimisticEditToFrame(acc, edit)
        }
    }

    /**
     * Parses ANSI SGR and cursor escape sequences in [ansiText], wraps lines cleanly to [rect.width]
     * (clamping horizontal box-drawing divider lines to 1 row), and populates [grid] inside [rect],
     * returning the final `(col, row)` cursor coordinates.
     */
    private fun renderAnsiIntoRect(
        ansiText: String,
        rect: SurfaceRect,
        grid: Array<CellData>,
        gridCols: Int,
        gridRows: Int,
    ): Pair<Int, Int> {
        val startX = rect.x.toInt().coerceIn(0, max(0, gridCols - 1))
        val startY = rect.y.toInt().coerceIn(0, max(0, gridRows - 1))
        val maxW = rect.width.toInt().coerceAtLeast(1)
        val maxH = rect.height.toInt().coerceAtLeast(1)
        val endX = min(gridCols, startX + maxW)
        val endY = min(gridRows, startY + maxH)
        val usableW = (endX - startX).coerceAtLeast(1)
        val usableH = (endY - startY).coerceAtLeast(1)

        val rawLines = ansiText.replace("\r\n", "\n").split('\n')

        var fg: UInt = 0u
        var bg: UInt = 0u
        var mod: UShort = 0u

        val wrappedRows = ArrayList<List<CellData>>(rawLines.size + 16)

        for (line in rawLines) {
            val lineCells = ArrayList<CellData>(min(line.length.coerceAtLeast(16), 256))
            var col = 0
            var i = 0
            val maxLineCols = 512

            fun ensureCol(targetCol: Int) {
                while (lineCells.size < targetCol && lineCells.size < maxLineCols) {
                    lineCells.add(BLANK_CELL)
                }
            }

            while (i < line.length) {
                val ch = line[i]
                if (ch == '\u001b') {
                    if (i + 1 < line.length && line[i + 1] == '[') {
                        var j = i + 2
                        while (j < line.length && line[j].code !in 0x40..0x7E) {
                            j++
                        }
                        if (j < line.length) {
                            val finalChar = line[j]
                            val paramsStr = line.substring(i + 2, j)
                            when (finalChar) {
                                'm' -> {
                                    val sgrState = applySgrCodes(paramsStr, fg, bg, mod)
                                    fg = sgrState.fg
                                    bg = sgrState.bg
                                    mod = sgrState.mod
                                }
                                'C' -> {
                                    val n = paramsStr.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                    col = (col + n).coerceAtMost(maxLineCols)
                                    ensureCol(col)
                                }
                                'D' -> {
                                    val n = paramsStr.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                    col = (col - n).coerceAtLeast(0)
                                }
                                'G' -> {
                                    val n = paramsStr.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                    col = (n - 1).coerceIn(0, maxLineCols)
                                    ensureCol(col)
                                }
                                'K' -> {
                                    val mode = paramsStr.toIntOrNull() ?: 0
                                    if (mode == 0 && col < lineCells.size) {
                                        while (lineCells.size > col) {
                                            lineCells.removeAt(lineCells.lastIndex)
                                        }
                                    } else if (mode == 2) {
                                        lineCells.clear()
                                    }
                                }
                            }
                            i = j + 1
                            continue
                        }
                    } else if (i + 1 < line.length && line[i + 1] == ']') {
                        var j = i + 2
                        while (j < line.length && line[j] != '\u0007') {
                            if (line[j] == '\u001b' && j + 1 < line.length && line[j + 1] == '\\') {
                                j++
                                break
                            }
                            j++
                        }
                        i = (j + 1).coerceAtMost(line.length)
                        continue
                    }
                    i++
                    continue
                }

                if (ch == '\r') {
                    col = 0
                    i++
                    continue
                }
                if (ch == '\t') {
                    val nextStop = ((col / 4) + 1) * 4
                    col = nextStop.coerceAtMost(maxLineCols)
                    ensureCol(col)
                    i++
                    continue
                }
                if (ch == '\b') {
                    col = max(0, col - 1)
                    i++
                    continue
                }

                val cp = Character.codePointAt(line, i)
                val charCount = Character.charCount(cp)
                if (cp < 32 || cp == 0x7F) {
                    i += charCount
                    continue
                }

                val charType = Character.getType(cp)
                val isZeroWidth = cp in 0xFE00..0xFE0F ||
                    cp == 0x200B ||
                    cp == 0x200C ||
                    cp == 0x200D ||
                    charType == Character.NON_SPACING_MARK.toInt() ||
                    charType == Character.ENCLOSING_MARK.toInt() ||
                    charType == Character.FORMAT.toInt()
                if (isZeroWidth) {
                    i += charCount
                    continue
                }

                if (col < maxLineCols) {
                    ensureCol(col)
                    val cell = CellData(
                        symbol = codepointToSymbol(cp),
                        fg = fg,
                        bg = bg,
                        modifier = mod,
                    )
                    if (col < lineCells.size) {
                        lineCells[col] = cell
                    } else {
                        lineCells.add(cell)
                    }
                    col++
                }
                i += charCount
            }

            // Trim trailing unstyled spaces so padding doesn't wrap into blank rows
            while (lineCells.isNotEmpty()) {
                val tail = lineCells.last()
                if ((tail.symbol.isEmpty() || tail.symbol == " ") && tail.bg == 0u && tail.modifier == 0u.toUShort()) {
                    lineCells.removeAt(lineCells.lastIndex)
                } else {
                    break
                }
            }

            if (lineCells.size <= usableW) {
                wrappedRows.add(lineCells)
            } else {
                // Check if this row is predominantly a horizontal rule (──────)
                var nonSpaceCount = 0
                var hLineCount = 0
                for (c in lineCells) {
                    val s = c.symbol
                    if (s.isNotEmpty() && s != " ") {
                        nonSpaceCount++
                        val code = s[0].code
                        if (code in 0x2500..0x2509 || code == 0x254C || code == 0x254D || code == 0x2550 || code == '-'.code || code == '='.code) {
                            hLineCount++
                        }
                    }
                }
                val isHorizontalDivider = nonSpaceCount >= 8 && hLineCount * 4 >= nonSpaceCount * 3
                if (isHorizontalDivider) {
                    wrappedRows.add(lineCells.subList(0, usableW))
                } else {
                    var offset = 0
                    while (offset < lineCells.size) {
                        val end = min(lineCells.size, offset + usableW)
                        wrappedRows.add(lineCells.subList(offset, end))
                        offset = end
                    }
                }
            }
        }

        // Trim trailing empty rows at the very bottom if they exceed usableH, then take the last usableH rows
        while (wrappedRows.size > usableH && wrappedRows.last().isEmpty()) {
            wrappedRows.removeAt(wrappedRows.lastIndex)
        }
        val visibleRows = if (wrappedRows.size > usableH) {
            wrappedRows.takeLast(usableH)
        } else {
            wrappedRows
        }

        var lastCol = startX
        var lastRow = startY

        for (rowIdx in visibleRows.indices) {
            val row = startY + rowIdx
            if (row >= endY) break
            val rowCells = visibleRows[rowIdx]
            for (colIdx in rowCells.indices) {
                val col = startX + colIdx
                if (col >= endX) break
                val cell = rowCells[colIdx]
                grid[row * gridCols + col] = cell
                if (cell.symbol.isNotEmpty() && cell.symbol != " ") {
                    lastCol = col
                    lastRow = row
                }
            }
        }
        return min(endX - 1, lastCol + 1) to lastRow
    }

    private fun codepointToSymbol(cp: Int): String =
        if (cp in 0..<128) ASCII_SYMBOLS[cp] else String(Character.toChars(cp))

    private fun applySgrCodes(
        paramsStr: String,
        currentFg: UInt,
        currentBg: UInt,
        currentMod: UShort,
    ): SgrState {
        if (paramsStr.isEmpty()) {
            return RESET_SGR_STATE
        }
        val rawTokens = paramsStr.split(';')
        var fg = currentFg
        var bg = currentBg
        var mod = currentMod.toInt()
        var idx = 0
        while (idx < rawTokens.size) {
            val token = rawTokens[idx]
            if (token.contains(':')) {
                val sub = token.split(':')
                val mainCode = sub.firstOrNull()?.toIntOrNull() ?: -1
                if (mainCode == 4) {
                    when (sub.getOrNull(1)?.toIntOrNull() ?: 1) {
                        0 -> mod = mod and (0x0008 or 0x0010).inv()
                        3 -> mod = mod or 0x0010
                        else -> mod = mod or 0x0008
                    }
                }
                idx++
                continue
            }
            when (val c = if (token.isEmpty()) 0 else (token.toIntOrNull() ?: -1)) {
                0 -> {
                    fg = 0u
                    bg = 0u
                    mod = 0
                }
                1 -> mod = mod or 0x0001
                2 -> mod = mod or 0x0002
                3 -> mod = mod or 0x0004
                4 -> mod = mod or 0x0008
                7 -> mod = mod or 0x0040
                9 -> mod = mod or 0x0100
                22 -> mod = mod and (0x0001 or 0x0002).inv()
                23 -> mod = mod and 0x0004.inv()
                24 -> mod = mod and (0x0008 or 0x0010).inv()
                27 -> mod = mod and 0x0040.inv()
                29 -> mod = mod and 0x0100.inv()
                in 30..37 -> fg = 0x01_00_00_00u or (c - 30).toUInt()
                39 -> fg = 0u
                in 40..47 -> bg = 0x01_00_00_00u or (c - 40).toUInt()
                49 -> bg = 0u
                in 90..97 -> fg = 0x01_00_00_00u or (c - 90 + 8).toUInt()
                in 100..107 -> bg = 0x01_00_00_00u or (c - 100 + 8).toUInt()
                38 -> {
                    val mode = rawTokens.getOrNull(idx + 1)?.toIntOrNull()
                    if (mode == 5 && idx + 2 < rawTokens.size) {
                        val n = (rawTokens[idx + 2].toIntOrNull() ?: 0).coerceIn(0, 255)
                        fg = 0x01_00_00_00u or n.toUInt()
                        idx += 2
                    } else if (mode == 2 && idx + 4 < rawTokens.size) {
                        val r = (rawTokens[idx + 2].toIntOrNull() ?: 0).coerceIn(0, 255)
                        val g = (rawTokens[idx + 3].toIntOrNull() ?: 0).coerceIn(0, 255)
                        val b = (rawTokens[idx + 4].toIntOrNull() ?: 0).coerceIn(0, 255)
                        fg = 0x02_00_00_00u or (r.toUInt() shl 16) or (g.toUInt() shl 8) or b.toUInt()
                        idx += 4
                    }
                }
                48 -> {
                    val mode = rawTokens.getOrNull(idx + 1)?.toIntOrNull()
                    if (mode == 5 && idx + 2 < rawTokens.size) {
                        val n = (rawTokens[idx + 2].toIntOrNull() ?: 0).coerceIn(0, 255)
                        bg = 0x01_00_00_00u or n.toUInt()
                        idx += 2
                    } else if (mode == 2 && idx + 4 < rawTokens.size) {
                        val r = (rawTokens[idx + 2].toIntOrNull() ?: 0).coerceIn(0, 255)
                        val g = (rawTokens[idx + 3].toIntOrNull() ?: 0).coerceIn(0, 255)
                        val b = (rawTokens[idx + 4].toIntOrNull() ?: 0).coerceIn(0, 255)
                        bg = 0x02_00_00_00u or (r.toUInt() shl 16) or (g.toUInt() shl 8) or b.toUInt()
                        idx += 4
                    }
                }
                58 -> {
                    // Underline color: consume its arguments so they aren't misinterpreted
                    val mode = rawTokens.getOrNull(idx + 1)?.toIntOrNull()
                    if (mode == 5 && idx + 2 < rawTokens.size) {
                        idx += 2
                    } else if (mode == 2 && idx + 4 < rawTokens.size) {
                        idx += 4
                    }
                }
            }
            idx++
        }
        return SgrState(fg, bg, mod.toUShort())
    }

    private fun stripAnsiCodes(text: String): String =
        text.replace(CSI_REGEX, "")
            .replace(OSC_REGEX, "")

    private fun parseSurfaceRect(rectObj: JsonObject): SurfaceRect =
        SurfaceRect(
            x = (rectObj["x"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0).toUShort(),
            y = (rectObj["y"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0).toUShort(),
            width = (rectObj["width"]?.jsonPrimitive?.intOrNull ?: 80).coerceAtLeast(1).toUShort(),
            height = (rectObj["height"]?.jsonPrimitive?.intOrNull ?: 24).coerceAtLeast(1).toUShort(),
        )

    private fun parseAgentStatus(raw: String?): AgentStatus =
        when (raw?.trim()?.lowercase()) {
            "blocked" -> AgentStatus.Blocked
            "working", "running" -> AgentStatus.Working
            "done", "completed" -> AgentStatus.Done
            "idle" -> AgentStatus.Idle
            else -> AgentStatus.Unknown
        }

    companion object {
        private const val SOCKET_CONNECT_TIMEOUT_MS = 6_000
        private const val CHANNEL_CONNECT_TIMEOUT_MS = 5_000
        private const val IPTOS_LOWDELAY = 0x10
        private const val LIVE_POLL_INTERVAL_MS = 400L
        private const val PTY_ECHO_SETTLE_DELAY_MS = 18L
        private const val OPTIMISTIC_ECHO_TTL_MS = 1_500L
        private const val MAX_OPTIMISTIC_EDITS = 128
        private const val PANE_SCROLL_ACCEPTED_RESPONSE = """{"id":"pane_scroll","result":{"type":"pane_scroll_queued"}}"""

        private val BLANK_CELL = CellData()
        private val RESET_SGR_STATE = SgrState(0u, 0u, 0u)
        private val ASCII_SYMBOLS = Array(128) { it.toChar().toString() }
        private val CSI_REGEX = Regex("\u001b\\[[0-9;?]*[ -/]*[@-~]")
        private val OSC_REGEX = Regex("\u001b\\][^\u0007]*\u0007")
    }
}
